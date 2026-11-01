package io.github.springmcp.core.analyze;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import io.github.springmcp.core.index.AnnotationArg;
import io.github.springmcp.core.index.AnnotationUse;
import io.github.springmcp.core.index.MethodSummary;
import io.github.springmcp.core.index.TypeKind;
import io.github.springmcp.core.index.TypeSummary;
import io.github.springmcp.core.resolve.SuperTypeGraph;
import io.github.springmcp.core.resolve.TypeNameResolver;
import io.github.springmcp.core.util.SpringAnnotations;
import io.github.springmcp.spi.model.Mappers;

/**
 * Collects MyBatis annotation mappers, MyBatis XML mappers and Spring Data JPA repositories, then
 * links the interface and XML sides by namespace.
 *
 * <p>
 * The linking step is the point: a MyBatis mapper is usually an interface with no SQL at all plus
 * an XML file with all of it, and a caller asking "what does UserMapper do" needs both halves in one
 * answer.
 * </p>
 */
public final class MapperAnalyzer {

    private static final Set<String> STATEMENT_TAGS = Set.of("select", "insert", "update", "delete");

    /** Bound on how far an entity type binding is followed up an inheritance chain. */
    private static final int MAX_GENERIC_DEPTH = 6;

    private final SuperTypeGraph superTypes;

    /**
     * @param superTypes used to detect JPA repositories through their inheritance chain, and to
     * read the generic arguments of {@code JpaRepository<Entity, Id>}
     */
    public MapperAnalyzer(SuperTypeGraph superTypes) {
        this.superTypes = superTypes;
    }

    /**
     * Cached XML discovery for this snapshot.
     *
     * <p>
     * Discovering mapper XML means walking the project tree, which on Windows costs roughly 1s per
     * 8 000 files because every file needs a stat call. That cost belongs to the snapshot, not to the
     * call: a new {@code MapperAnalyzer} is created for every rebuild, so memoising here invalidates
     * automatically when the sources change, and repeated {@code list-mappers} calls drop to the cost
     * of the actual DOM parses.
     * </p>
     */
    private volatile XmlScan xmlScan;

    /** Cached result of one XML discovery pass. */
    private record XmlScan(List<Path> files, List<String> warnings) {
    }

    /**
     * Analyses every mapper of the project.
     * @param types every type in the index
     * @param root project root, used to locate {@code resources} directories
     * @param query filters
     * @param includeTests whether test resources should be scanned
     * @return the mapper report
     */
    public Mappers.Report analyze(List<TypeSummary> types, Path root, Mappers.Query query, boolean includeTests) {
        return analyze(types, root, query, includeTests, xml -> true);
    }

    /**
     * Analyses the mappers of a subset of the project.
     *
     * <p>
     * The XML discovery pass is shared: it is memoised per snapshot and the filter is applied to its
     * result, so switching scope never re-walks the tree. Filtering here matters more than it looks —
     * a repo-wide scan finds every module's {@code resources/mapper/*.xml}, and an unscoped XML
     * would attach statements to a mapper the caller explicitly excluded.
     * </p>
     *
     * @param types types to consider, already narrowed by the caller
     * @param root project root, used to locate {@code resources} directories
     * @param query filters
     * @param includeTests whether test resources should be scanned
     * @param xmlInScope which mapper XML files may contribute statements
     * @return the mapper report
     */
    public Mappers.Report analyze(List<TypeSummary> types, Path root, Mappers.Query query, boolean includeTests,
            Predicate<Path> xmlInScope) {
        XmlScan scan = xmlScan;
        if (scan == null) {
            List<String> scanWarnings = new ArrayList<>();
            List<Path> files = findMapperXml(root, includeTests, scanWarnings);
            scan = new XmlScan(files, List.copyOf(scanWarnings));
            xmlScan = scan;
        }

        List<String> warnings = new ArrayList<>(scan.warnings());

        // Declaration styles differ by generation: @Mapper on the interface (classic MyBatis),
        // extends BaseMapper<T> (MyBatis-Plus), or nothing at all plus @MapperScan on a config class.
        // Only checking the first one finds a third of the mappers in a MyBatis-Plus project.
        Set<String> scannedPackages = mapperScanPackages(types);

        // fqn -> descriptor being assembled, insertion ordered for stable output.
        Map<String, DescriptorBuilder> byFqn = new LinkedHashMap<>();

        for (TypeSummary type : types) {
            if (type.kind() != TypeKind.INTERFACE) {
                continue;
            }
            boolean mybatis = type.hasAnnotation(SpringAnnotations.MYBATIS_MAPPER)
                    || superTypes.anySuperTypeNamed(type.fqn(), SpringAnnotations.MYBATIS_PLUS_BASE_TYPES)
                    || inScannedPackage(type.packageName(), scannedPackages);
            boolean jpa = superTypes.anySuperTypeNamed(type.fqn(), SpringAnnotations.JPA_REPOSITORY_TYPES);
            if (!mybatis && !jpa) {
                continue;
            }
            DescriptorBuilder b = new DescriptorBuilder(type, jpa ? Mappers.Kind.JPA : Mappers.Kind.ANNOTATION);
            if (jpa) {
                fillJpa(type, b, warnings);
            }
            else {
                fillMybatisAnnotations(type, b);
                // BaseMapper<Entity> names the entity; it is the only generic a plain MyBatis mapper
                // has, so a caller asking "which table does this touch" gets an answer.
                b.entityType = entityOf(findBaseMapperDeclaration(type));
            }
            byFqn.put(type.fqn(), b);
        }

        List<String> orphans = new ArrayList<>();
        for (Path xml : scan.files()) {
            if (!xmlInScope.test(xml)) {
                continue;
            }
            parseXml(xml, byFqn, orphans, warnings);
        }

        List<Mappers.Descriptor> descriptors = new ArrayList<>();
        for (DescriptorBuilder b : byFqn.values()) {
            if (!matches(b.simpleName, query.nameFilter())) {
                continue;
            }
            if (!query.kinds().isEmpty() && !query.kinds().contains(b.kind)) {
                continue;
            }
            descriptors.add(b.build());
        }
        // Total order, not "by simple name": two mappers with the same simple name in different
        // packages are common (CityMapper appears seven times in mybatis-spring-boot), and leaving the
        // tie to encounter order makes the JSON differ between runs.
        descriptors.sort(Comparator.comparing(Mappers.Descriptor::simpleName)
            .thenComparing(descriptor -> descriptor.interfaceFqn() == null ? "" : descriptor.interfaceFqn()));

        // Counts in enum order for the same reason: a LinkedHashMap filled while walking the index
        // took its key order from that walk, which is not part of the contract.
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Mappers.Kind kind : Mappers.Kind.values()) {
            int count = 0;
            for (Mappers.Descriptor descriptor : descriptors) {
                if (descriptor.kind() == kind) {
                    count++;
                }
            }
            if (count > 0) {
                counts.put(kind.name(), count);
            }
        }

        if (!orphans.isEmpty()) {
            warnings.add(orphans.size() + " 个 XML mapper 的 namespace 在工程里找不到对应接口");
        }

        return new Mappers.Report(descriptors, orphans, counts, warnings);
    }

    // ------------------------------------------------------------------ interfaces

    private void fillMybatisAnnotations(TypeSummary type, DescriptorBuilder b) {
        for (MethodSummary m : type.plainMethods()) {
            b.declaredMethods.add(m.name());
            AnnotationUse stmt = null;
            for (AnnotationUse a : m.annotations()) {
                if (SpringAnnotations.MYBATIS_STATEMENT_ANNOTATIONS.contains(a.simpleName())) {
                    stmt = a;
                    break;
                }
            }
            if (stmt != null) {
                b.statements.add(new Mappers.Statement(type.fqn() + "." + m.name(),
                        SpringAnnotations.mybatisCommandType(stmt.simpleName()), m.name(), null, null, List.of(),
                        false, m.line()));
            }
        }
    }

    private void fillJpa(TypeSummary type, DescriptorBuilder b, List<String> warnings) {
        for (MethodSummary m : type.plainMethods()) {
            b.declaredMethods.add(m.name());
            AnnotationUse queryAnn = m.annotation("Query");
            if (queryAnn != null) {
                b.statements.add(new Mappers.Statement(type.fqn() + "." + m.name(), "QUERY", m.name(), null, null,
                        List.of(), false, m.line()));
            }
        }
        String rawRepo = findRepositoryDeclaration(type);
        if (rawRepo != null) {
            b.entityType = typeArgumentOrNull(rawRepo, 0);
            b.idType = typeArgumentOrNull(rawRepo, 1);
        }
        if (b.entityType == null) {
            warnings.add("无法从泛型声明推断 " + type.fqn() + " 的实体类型（继承链上的类型参数无法绑定）");
        }
    }

    private String findRepositoryDeclaration(TypeSummary type) {
        return findGenericDeclaration(type, SpringAnnotations.JPA_REPOSITORY_TYPES, Map.of(), 0,
                new HashSet<>(Set.of(type.fqn())));
    }

    private String findBaseMapperDeclaration(TypeSummary type) {
        return findGenericDeclaration(type, SpringAnnotations.MYBATIS_PLUS_BASE_TYPES, Map.of(), 0,
                new HashSet<>(Set.of(type.fqn())));
    }

    /**
     * Finds the declaration that binds the entity type, following intermediate interfaces.
     *
     * <p>
     * {@code interface CoinDao extends BaseDao<Coin>} plus {@code interface BaseDao<T> extends
     * JpaRepository<T, Long>} is an extremely common way to share CRUD plumbing, and reading only the
     * direct {@code extends} clause reported the entity of every such repository as unknown: 82
     * mappers in the regression corpus, 54 of them in a single project.
     * </p>
     *
     * <p>
     * Walking up is only half of it — the argument has to be carried along, because the base writes
     * {@code T} where the subclass wrote {@code Coin}. The binding is positional and comes from the
     * declaration's own type parameter list, which is why that list is part of the index. Each step is
     * resolved in the declaring file's import context, so {@code BaseDao} means the one that file
     * imported, not a same-named class elsewhere. When the intermediate type is outside the index, or
     * the name is genuinely ambiguous, the walk stops and the answer stays unknown rather than
     * guessed.
     * </p>
     *
     * @param type type to inspect
     * @param candidates simple names of the framework base types
     * @param bindings type variable substitutions inherited from the caller
     * @param depth how many levels have been climbed, bounded by {@link #MAX_GENERIC_DEPTH}
     * @param seen types already visited, so a cyclic hierarchy terminates
     * @return the supertype declaration with arguments substituted, e.g.
     * {@code JpaRepository<Coin, Long>}, or {@code null}
     */
    private String findGenericDeclaration(TypeSummary type, Set<String> candidates, Map<String, String> bindings,
            int depth, Set<String> seen) {
        if (depth > MAX_GENERIC_DEPTH) {
            return null;
        }
        for (String raw : type.rawSuperTypes()) {
            String simple = simpleNameOf(raw);
            if (candidates.contains(simple)) {
                return substitute(raw, bindings);
            }
            String resolved = superTypes.resolveSuperType(type.fqn(), raw);
            TypeSummary next = superTypes.typeByFqn(resolved);
            if (next == null || !seen.add(next.fqn())) {
                continue;
            }
            String found = findGenericDeclaration(next, candidates, bind(raw, next, bindings), depth + 1, seen);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * Maps a declaration's type parameters onto the arguments of one use of it.
     *
     * @param usage the {@code extends} clause, e.g. {@code BaseDao<Coin>}
     * @param declaration the intermediate type, whose parameters are {@code [T]}
     * @param inherited substitutions already in effect
     * @return substitutions to apply one level up
     */
    private static Map<String, String> bind(String usage, TypeSummary declaration, Map<String, String> inherited) {
        List<String> args = TypeNameResolver.typeArguments(usage);
        List<String> params = declaration.typeParameters();
        if (args.isEmpty() || params.isEmpty()) {
            return Map.of();
        }
        Map<String, String> bindings = new HashMap<>();
        for (int i = 0; i < args.size() && i < params.size(); i++) {
            bindings.put(params.get(i), substitute(args.get(i), inherited));
        }
        return bindings;
    }

    /**
     * Replaces type variables with their bound types.
     *
     * @param text type expression, e.g. {@code JpaRepository<T, Long>}
     * @param bindings variable to argument
     * @return the expression with variables replaced
     */
    private static String substitute(String text, Map<String, String> bindings) {
        if (bindings.isEmpty() || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (Map.Entry<String, String> binding : bindings.entrySet()) {
            result = result.replaceAll("(?<![A-Za-z0-9_$.])" + Pattern.quote(binding.getKey()) + "(?![A-Za-z0-9_$])",
                    java.util.regex.Matcher.quoteReplacement(binding.getValue()));
        }
        return result;
    }

    private static String simpleNameOf(String raw) {
        String name = TypeNameResolver.stripTypeArguments(raw);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name.trim() : name.substring(dot + 1).trim();
    }

    /**
     * Reads one generic argument, refusing to report a type variable as if it were a type.
     *
     * <p>
     * A generic base such as {@code interface MyBaseMapper<T> extends BaseMapper<T>} or
     * {@code interface BaseDao<T> extends JpaRepository<T, Long>} binds a parameter, not an entity.
     * Reporting "T" would look like a resolved answer while carrying no information — exactly the
     * failure mode this project refuses elsewhere — so it is reported as unknown instead. The
     * declaration's type parameter names are in the index, but the parameter list of the type being
     * inspected is not threaded through here; a single upper-case letter and a wildcard are the two
     * shapes recognised without it.
     * </p>
     *
     * @param raw the {@code extends} declaration, may be {@code null}
     * @param index which argument to read
     * @return the type as written, or {@code null} when there is nothing to report
     */
    private static String typeArgumentOrNull(String raw, int index) {
        String argument = TypeNameResolver.typeArgument(raw, index);
        if (argument == null) {
            return null;
        }
        String trimmed = argument.trim();
        boolean placeholder = trimmed.isEmpty() || trimmed.startsWith("?")
                || (trimmed.length() == 1 && Character.isUpperCase(trimmed.charAt(0)));
        return placeholder ? null : trimmed;
    }

    /**
     * Reads the entity type out of {@code BaseMapper<X>}.
     *
     * @param raw the {@code extends} declaration, may be {@code null}
     * @return the entity type as written, or {@code null} when there is none to report
     */
    private static String entityOf(String raw) {
        return typeArgumentOrNull(raw, 0);
    }

    /**
     * Collects the packages covered by {@code @MapperScan}.
     *
     * <p>
     * A mapper registered this way carries no {@code @Mapper}, so package coverage is the only
     * evidence that it is one. A bare {@code @MapperScan} means "the package this annotation sits in",
     * which is MyBatis' own documented behaviour and therefore not a guess.
     * </p>
     *
     * @param types every type in the index
     * @return package prefixes, empty when no {@code @MapperScan} is present
     */
    private static Set<String> mapperScanPackages(List<TypeSummary> types) {
        Set<String> packages = new LinkedHashSet<>();
        for (TypeSummary type : types) {
            for (AnnotationUse annotation : type.annotations()) {
                if (!annotation.simpleName().equals(SpringAnnotations.MAPPER_SCAN)) {
                    continue;
                }
                AnnotationArg arg = annotation.arg("basePackages");
                if (arg == null) {
                    arg = annotation.value();
                }
                if (arg == null) {
                    packages.add(type.packageName());
                    continue;
                }
                if (!arg.allStringLiterals()) {
                    // A constant or expression: the documented boundary is that such values are not
                    // evaluated, and inventing a package from them would be worse than missing one.
                    continue;
                }
                for (String value : arg.values()) {
                    String cleaned = value.trim();
                    if (!cleaned.isEmpty()) {
                        packages.add(cleaned);
                    }
                }
            }
        }
        return packages;
    }

    /**
     * @param packageName package of a candidate interface
     * @param packages prefixes collected from {@code @MapperScan}
     * @return whether the candidate sits in one of them, sub-packages included
     */
    private static boolean inScannedPackage(String packageName, Set<String> packages) {
        for (String pattern : packages) {
            if (matchesScannedPackage(pattern, packageName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Matches a package against one {@code @MapperScan} value, wildcards included.
     *
     * <p>
     * {@code @MapperScan("com.ruoyi.**.mapper")} is how RuoYi — and a great many projects derived from
     * it — declares its mappers. Plain prefix matching never matches that pattern, so every mapper in
     * such a project was reported as an orphaned XML namespace instead of an interface. MyBatis
     * resolves these patterns with Ant-style rules, and so does this method: {@code **} spans zero or
     * more whole segments, {@code *} spans one segment.
     * </p>
     *
     * @param pattern value from {@code @MapperScan}, possibly containing wildcards
     * @param packageName package of a candidate interface
     * @return whether the pattern covers the package
     */
    static boolean matchesScannedPackage(String pattern, String packageName) {
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        if (!pattern.contains("*")) {
            return packageName.equals(pattern) || packageName.startsWith(pattern + ".");
        }
        String[] parts = pattern.split("\\.");
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            boolean last = i == parts.length - 1;
            if ("**".equals(part)) {
                if (last) {
                    regex.append("[^.]+(?:\\.[^.]+)*");
                }
                else {
                    // Each repetition carries its own separator dot, so nothing is appended after.
                    regex.append("(?:[^.]+\\.)*");
                    continue;
                }
            }
            else if ("*".equals(part)) {
                regex.append("[^.]+");
            }
            else {
                regex.append(Pattern.quote(part));
            }
            if (!last) {
                regex.append("\\.");
            }
        }
        return Pattern.compile("^" + regex + "$").matcher(packageName).matches();
    }

    // ------------------------------------------------------------------ xml

    /**
     * Finds XML files that can plausibly be MyBatis mappers.
     *
     * <p>
     * The walk cannot filter by content, and a real multi-module repository contains far more
     * {@code pom.xml}, {@code logback.xml} and Spring config files than mappers — spring-boot's own
     * tree has roughly a thousand of them. Parsing all of those with a DOM builder cost ~1.5s in
     * measurement, which is pure waste since a mapper always declares {@code <mapper>} as its root
     * element.
     * </p>
     *
     * <p>
     * So each candidate gets a cheap 4 KB head read first. That is enough to see the root element of
     * any sane mapper file (the DOCTYPE preamble is ~150 bytes) and it turns the DOM stage into "only
     * the files that are actually mappers".
     * </p>
     */
    private List<Path> findMapperXml(Path root, boolean includeTests, List<String> warnings) {
        Set<Path> bases = new LinkedHashSet<>();
        bases.add(root.resolve("src").resolve("main").resolve("resources"));
        if (includeTests) {
            bases.add(root.resolve("src").resolve("test").resolve("resources"));
        }
        // Fall back to the project root so a plain "resources" folder or an unusual layout still works.
        bases.add(root);

        List<Path> out = new ArrayList<>();
        for (Path base : bases) {
            if (!Files.isDirectory(base)) {
                continue;
            }
            // Deep on purpose. A mapper XML sits at
            // <module>/src/main/resources/<package path>/Xxx.xml, which is already level 9 for a
            // three-segment package — the old limit of 8 silently dropped 76 of mall's 106 mapper
            // XMLs, and nothing reported it. A cap still exists so a pathological tree cannot make
            // this walk unbounded.
            try (Stream<Path> stream = Files.walk(base, 24)) {
                stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xml"))
                    .filter(p -> !isUnderBuildOutput(root, p))
                    // The Java side already skips every */src/test when tests are not wanted. Scanning
                    // test resources anyway produced XML-only entries whose namespace points at a test
                    // interface that was never indexed — pure noise in list-mappers.
                    .filter(p -> includeTests || !isUnderTestSource(root, p))
                    .filter(MapperAnalyzer::looksLikeMapperXml)
                    .sorted()
                    .forEach(out::add);
            }
            catch (IOException e) {
                warnings.add("扫描 XML 失败: " + base + " (" + e.getMessage() + ")");
            }
        }
        return out.stream().distinct().toList();
    }

    /**
     * Cheap content sniff: does the head of this file declare a {@code <mapper>} root element?
     *
     * <p>
     * Deliberately a substring check rather than a real parse. A false positive costs one DOM parse
     * that then rejects the file; a false negative would silently drop a mapper, so the check errs
     * towards inclusion by only rejecting files whose head contains no {@code <mapper} at all.
     * </p>
     *
     * @param xml candidate file
     * @return whether it is worth a full parse
     */
    private static boolean looksLikeMapperXml(Path xml) {
        try (var reader = Files.newBufferedReader(xml, StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int read = reader.read(buffer);
            if (read <= 0) {
                return false;
            }
            return new String(buffer, 0, read).contains("<mapper");
        }
        catch (IOException e) {
            // Unreadable or non-UTF-8 XML: not worth failing the scan over.
            return false;
        }
    }

    /**
     * @param root project root
     * @param file candidate XML
     * @return whether the file lives under any {@code src/test} directory
     */
    private static boolean isUnderTestSource(Path root, Path file) {
        String rel = root.relativize(file).toString().replace('\\', '/');
        return rel.startsWith("src/test/") || rel.contains("/src/test/");
    }

    private boolean isUnderBuildOutput(Path root, Path file) {
        String rel = root.relativize(file).toString().replace('\\', '/');
        for (String skip : new String[] { "target/", "build/", "out/", ".git/" }) {
            if (rel.startsWith(skip)) {
                return true;
            }
        }
        return false;
    }

    private void parseXml(Path xml, Map<String, DescriptorBuilder> byFqn, List<String> orphans, List<String> warnings) {
        Document doc;
        try {
            doc = secureBuilder().parse(xml.toFile());
        }
        catch (SAXException | IOException | ParserConfigurationException e) {
            warnings.add("XML 解析失败: " + xml + " (" + e.getMessage() + ")");
            return;
        }
        Element rootEl = doc.getDocumentElement();
        if (rootEl == null || !"mapper".equals(rootEl.getNodeName())) {
            // Not a MyBatis mapper (spring config, logback, ...). Silent skip is correct here.
            return;
        }
        String namespace = rootEl.getAttribute("namespace");
        if (namespace == null || namespace.isBlank()) {
            warnings.add("XML 缺少 namespace，已跳过: " + xml);
            return;
        }

        DescriptorBuilder b = byFqn.get(namespace);
        if (b == null) {
            b = new DescriptorBuilder(namespace, Mappers.Kind.XML);
            byFqn.put(namespace, b);
            orphans.add(xml.toString());
        }
        b.xmlPath = xml.toString();

        NodeList children = rootEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String tag = node.getNodeName();
            Element el = (Element) node;
            if (STATEMENT_TAGS.contains(tag)) {
                String id = el.getAttribute("id");
                b.statements.add(new Mappers.Statement(namespace + "." + id, tag.toUpperCase(Locale.ROOT), id,
                        emptyToNull(el.getAttribute("parameterType")),
                        emptyToNull(el.hasAttribute("resultType") ? el.getAttribute("resultType")
                                : el.getAttribute("resultMap")),
                        includeRefIds(el), false, 0));
            }
            else if ("sql".equals(tag)) {
                String id = el.getAttribute("id");
                b.statements.add(new Mappers.Statement(namespace + "." + id, "SQL", id, null, null, List.of(), true,
                        0));
            }
        }
    }

    private List<String> includeRefIds(Element statement) {
        List<String> refs = new ArrayList<>();
        NodeList includes = statement.getElementsByTagName("include");
        for (int i = 0; i < includes.getLength(); i++) {
            Element inc = (Element) includes.item(i);
            String refid = inc.getAttribute("refid");
            if (refid != null && !refid.isBlank()) {
                refs.add(refid);
            }
        }
        return refs;
    }

    /**
     * Builds a DOM parser that accepts the {@code DOCTYPE} every real MyBatis mapper carries, while
     * refusing to resolve any external entity.
     *
     * <p>
     * This combination is not optional. Setting {@code disallow-doctype-decl} to {@code true} — the
     * usual XXE hardening advice — makes <em>every</em> genuine mapper file fail to parse, because
     * they all start with {@code <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" ...>}.
     * Disabling external entity resolution plus an empty {@link org.xml.sax.EntityResolver} gives the
     * same protection without that false negative.
     * </p>
     */
    private DocumentBuilder secureBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        trySetFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", false);
        trySetFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        trySetFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        trySetFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        trySetAttribute(factory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
        trySetAttribute(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        builder.setErrorHandler(null);
        return builder;
    }

    private static void trySetFeature(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        }
        catch (ParserConfigurationException e) {
            // Parser does not know the feature; the remaining hardening still applies.
        }
    }

    private static void trySetAttribute(DocumentBuilderFactory factory, String name, String value) {
        try {
            factory.setAttribute(name, value);
        }
        catch (IllegalArgumentException e) {
            // Same reasoning as above.
        }
    }

    private static String emptyToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    private static boolean matches(String name, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        return name.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT));
    }

    private static boolean isTestSource(TypeSummary type) {
        return type.file().toString().replace('\\', '/').contains("/src/test/");
    }

    /** Mutable assembly state for one mapper. */
    private static final class DescriptorBuilder {

        private final String interfaceFqn;

        private final String simpleName;

        private final Mappers.Kind kind;

        private String xmlPath;

        private String entityType;

        private String idType;

        private final Set<String> declaredMethods = new LinkedHashSet<>();

        private final List<Mappers.Statement> statements = new ArrayList<>();

        private final List<String> issues = new ArrayList<>();

        DescriptorBuilder(TypeSummary type, Mappers.Kind kind) {
            this.interfaceFqn = type.fqn();
            this.simpleName = type.simpleName();
            this.kind = kind;
        }

        DescriptorBuilder(String interfaceFqn, Mappers.Kind kind) {
            this.interfaceFqn = interfaceFqn;
            int dot = interfaceFqn.lastIndexOf('.');
            this.simpleName = dot < 0 ? interfaceFqn : interfaceFqn.substring(dot + 1);
            this.kind = kind;
        }

        Mappers.Descriptor build() {
            // XML-backed mappers keep their XML statements; interface-only ones keep annotation
            // statements. Both lists are merged because a mapper can legitimately have both.
            List<Mappers.Statement> merged = new ArrayList<>(statements);
            return new Mappers.Descriptor(kind, interfaceFqn, simpleName, xmlPath, entityType, idType,
                    List.copyOf(declaredMethods), merged, issues);
        }
    }
}
