package io.github.springmcp.core.kotlin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.springmcp.core.index.AnnotationArg;
import io.github.springmcp.core.index.AnnotationUse;
import io.github.springmcp.core.index.CuSummary;
import io.github.springmcp.core.index.FieldSummary;
import io.github.springmcp.core.index.ImportInfo;
import io.github.springmcp.core.index.MethodSummary;
import io.github.springmcp.core.index.ParameterSummary;
import io.github.springmcp.core.index.TypeKind;
import io.github.springmcp.core.index.TypeSummary;

/**
 * Reduces a Kotlin source file to the same {@link CuSummary} a Java file produces.
 *
 * <p>
 * The whole point is that downstream does not change: controllers, mappers, implementations,
 * annotations and file dependencies all read {@code TypeSummary} and {@code MethodSummary}, so a
 * Kotlin controller that lands in the index correctly is found by every tool that finds a Java one.
 * </p>
 *
 * <p>
 * This is a declaration parser, not a compiler. It recognises the shapes that carry structure —
 * classes, interfaces, objects, functions, properties, annotations, supertypes — and skips
 * everything else by jumping over balanced brackets. It does not infer expression types, resolve
 * symbols, or evaluate constants; a path built from a constant is reported exactly like its Java
 * counterpart, as an unresolved expression.
 * </p>
 *
 * <p>
 * The limits are deliberate and documented in the README: expression-level type inference is not
 * attempted, so a property declared as {@code val repo = OrderRepository()} is typed by looking at
 * the constructor name, and anything more elaborate is left blank rather than guessed.
 * </p>
 */
public final class KotlinSummaryBuilder {

    /**
     * Builds a summary from Kotlin source.
     * @param file absolute source path
     * @param module project-relative module, {@code ""} when the project has no module structure
     * @param source file content
     * @param size file size in bytes at index time
     * @param lastModifiedMillis modification time at index time
     * @return a summary shaped exactly like the one a Java file would produce
     */
    public CuSummary build(Path file, String module, String source, long size, long lastModifiedMillis) {
        return new Parser(file, module, source).parse(size, lastModifiedMillis);
    }

    /** Single use, so the cursor and the collected state can live in fields. */
    private static final class Parser {

        /** Keywords that modify the declaration that follows and carry no meaning of their own. */
        private static final Set<String> MODIFIERS = Set.of("public", "private", "protected", "internal", "open",
                "abstract", "final", "sealed", "data", "inner", "value", "inline", "noinline", "crossinline", "vararg",
                "reified", "suspend", "override", "operator", "infix", "tailrec", "external", "lateinit", "const",
                "annotation", "enum", "actual", "expect");

        /** Words that can only start a member, used to end a declaration that has no terminator. */
        private static final Set<String> DECLARATION_WORDS = Set.of("public", "private", "protected", "internal",
                "open", "abstract", "final", "sealed", "data", "inner", "value", "inline", "noinline", "crossinline",
                "vararg", "reified", "suspend", "override", "operator", "infix", "tailrec", "external", "lateinit",
                "const", "annotation", "enum", "actual", "expect", "fun", "val", "var", "class", "interface", "object",
                "companion", "constructor", "init", "typealias", "get", "set");

        /** Parameter modifiers that do not turn a parameter into a property. */
        private static final Set<String> PARAM_MODIFIERS = Set.of("vararg", "noinline", "crossinline", "private",
                "public", "protected", "internal", "override");

        /** Rendered without a preceding space. */
        private static final Set<String> TIGHT_BEFORE = Set.of(")", "]", "}", ",", ";", ".", "?", ":", ">", "::", "<");

        /** Rendered without a following space. */
        private static final Set<String> TIGHT_AFTER = Set.of("(", "[", "<", ".", "@", "::");

        private final Path file;

        private final String module;

        private final List<KotlinToken> tokens;

        private final int[] match;

        private final List<AnnotationUse> noAnnotations = List.of();

        private final List<ImportInfo> imports = new ArrayList<>();

        private final List<TypeSummary> types = new ArrayList<>();

        private final List<String> problems = new ArrayList<>();

        private final Set<String> calledMethodNames = new LinkedHashSet<>();

        private final Set<String> referencedTypeNames = new LinkedHashSet<>();

        private int i;

        /** Where the last {@link #findBodyStart} scan stopped: the next declaration or end of input. */
        private int stoppedAt;

        private String packageName = "";

        Parser(Path file, String module, String source) {
            this.file = file;
            this.module = module;
            KotlinLexer.Result lexed = KotlinLexer.tokenize(source);
            this.tokens = lexed.tokens();
            this.problems.addAll(lexed.problems());
            this.match = KotlinBrackets.match(this.tokens);
        }

        CuSummary parse(long size, long lastModifiedMillis) {
            collectUses();
            parseFile();
            return new CuSummary(file, module, packageName, imports, types, calledMethodNames, referencedTypeNames,
                    problems, lastModifiedMillis, size);
        }

        // ------------------------------------------------------------ pre-pass

        /**
         * Collects the two cheap sets every summary carries.
         *
         * <p>
         * Both are over-approximations on purpose. A name that appears in a call position might be a
         * declaration, and a capitalised identifier might be a constant; including both only widens a
         * filter or an advisory list, while missing one would silently drop a file from a search.
         * </p>
         */
        private void collectUses() {
            for (int k = 0; k < tokens.size(); k++) {
                KotlinToken token = tokens.get(k);
                if (token.kind() != KotlinToken.Kind.IDENT) {
                    continue;
                }
                if (k + 1 < tokens.size() && tokens.get(k + 1).isSym("(")) {
                    calledMethodNames.add(token.text());
                }
                if (k > 0 && tokens.get(k - 1).isSym("@")) {
                    // An annotation name is not a type reference in the sense a dependency needs.
                    continue;
                }
                String text = token.text();
                if (!text.isEmpty() && Character.isUpperCase(text.charAt(0))) {
                    referencedTypeNames.add(text);
                }
            }
        }

        // ------------------------------------------------------------ file level

        private void parseFile() {
            List<AnnotationUse> annotations = new ArrayList<>();
            List<String> modifiers = new ArrayList<>();
            while (!eof()) {
                KotlinToken token = cur();
                if (token.isSym("@")) {
                    annotations.add(parseAnnotation());
                    continue;
                }
                if (token.isSym(";")) {
                    i++;
                    annotations.clear();
                    modifiers.clear();
                    continue;
                }
                if (token.isIdent("package")) {
                    i++;
                    packageName = readQualifiedName();
                    annotations.clear();
                    modifiers.clear();
                    continue;
                }
                if (token.isIdent("import")) {
                    i++;
                    readImport();
                    annotations.clear();
                    modifiers.clear();
                    continue;
                }
                if (isTypeKeyword(token)) {
                    parseType(annotations, modifiers, null, null);
                    annotations.clear();
                    modifiers.clear();
                    continue;
                }
                if (token.isIdent("fun")) {
                    // A top-level function is not a member of any type, so only its body is skipped.
                    parseFunction(annotations, modifiers, "");
                    annotations.clear();
                    modifiers.clear();
                    continue;
                }
                if (token.isIdent("val") || token.isIdent("var") || token.isIdent("typealias")) {
                    skipStatement(tokens.size() - 1);
                    annotations.clear();
                    modifiers.clear();
                    continue;
                }
                if (token.kind() == KotlinToken.Kind.IDENT && MODIFIERS.contains(token.text())) {
                    modifiers.add(token.text());
                    i++;
                    continue;
                }
                i++;
                annotations.clear();
                modifiers.clear();
            }
        }

        private void readImport() {
            int line = cur().line();
            StringBuilder name = new StringBuilder();
            boolean wildcard = false;
            if (!eof() && cur().kind() == KotlinToken.Kind.IDENT) {
                name.append(cur().text());
                i++;
            }
            while (!eof()) {
                KotlinToken token = cur();
                if (token.isSym(".") && i + 1 < tokens.size()) {
                    KotlinToken next = tokens.get(i + 1);
                    if (next.kind() == KotlinToken.Kind.IDENT) {
                        i++;
                        name.append('.').append(next.text());
                        i++;
                        continue;
                    }
                    if (next.isSym("*")) {
                        i += 2;
                        wildcard = true;
                        continue;
                    }
                }
                if (token.isIdent("as")) {
                    // Aliases are dropped: the import still names the type, and a local alias is not a
                    // project type, so keeping it would only create a name nothing can resolve.
                    i += 2;
                    continue;
                }
                break;
            }
            if (name.length() > 0) {
                imports.add(new ImportInfo(name.toString(), false, wildcard, line));
            }
        }

        // ------------------------------------------------------------ types

        private void parseType(List<AnnotationUse> annotations, List<String> modifiers, String outerFqn,
                String forcedSimpleName) {
            int slot = types.size();
            int line = cur().line();
            int column = cur().column();
            String keyword = cur().text();
            i++;

            String simple = forcedSimpleName;
            if (!eof() && cur().kind() == KotlinToken.Kind.IDENT) {
                simple = cur().text();
                i++;
            }
            if (simple == null) {
                // `object : Foo { }` is an expression, not a declaration.
                skipAnonymousObject();
                return;
            }
            List<String> typeParams = isSym("<") ? readTypeParameters() : List.of();

            List<FieldSummary> fields = new ArrayList<>();
            List<MethodSummary> methods = new ArrayList<>();
            List<String> supers = new ArrayList<>();

            if (isSym("(")) {
                int close = match[i];
                if (close > i) {
                    List<ParameterSummary> params = new ArrayList<>();
                    parseParameters(i, close, params, fields);
                    if (!params.isEmpty()) {
                        // Kotlin's primary constructor is the injection point; recording it keeps
                        // `class X(val svc: Svc)` readable as a dependency like its Java equivalent.
                        methods.add(new MethodSummary(simple, null, params, noAnnotations, true, true, false, line,
                                column));
                    }
                    i = close + 1;
                }
            }

            int bodyStart = findBodyStart(i);
            int supersEnd = bodyStart >= 0 ? bodyStart : stoppedAt;
            if (isSym(":")) {
                readSuperTypes(i + 1, supersEnd, supers);
            }
            i = bodyStart >= 0 ? bodyStart : supersEnd;

            String fqn = outerFqn != null ? outerFqn + "." + simple
                    : (packageName.isEmpty() ? simple : packageName + "." + simple);

            if (bodyStart >= 0 && tokens.get(bodyStart).isSym("{")) {
                parseBody(fqn, simple, fields, methods);
            }

            TypeKind kind = kindOf(keyword, modifiers);
            boolean abstractType = modifiers.contains("abstract") || modifiers.contains("sealed");
            types.add(slot, new TypeSummary(fqn, simple, packageName, kind, abstractType, typeParams, supers,
                    annotations, fields, methods, file, line, column));
        }

        private static TypeKind kindOf(String keyword, List<String> modifiers) {
            if ("interface".equals(keyword)) {
                return TypeKind.INTERFACE;
            }
            if ("class".equals(keyword)) {
                if (modifiers.contains("enum")) {
                    return TypeKind.ENUM;
                }
                if (modifiers.contains("annotation")) {
                    return TypeKind.ANNOTATION;
                }
            }
            return TypeKind.CLASS;
        }

        private void parseBody(String typeFqn, String typeName, List<FieldSummary> fields,
                List<MethodSummary> methods) {
            int close = match[i];
            if (close < 0) {
                i = tokens.size() - 1;
                return;
            }
            i++;
            List<AnnotationUse> annotations = new ArrayList<>();
            List<String> modifiers = new ArrayList<>();
            while (i < close) {
                KotlinToken token = cur();
                if (token.isSym("@")) {
                    annotations.add(parseAnnotation());
                    continue;
                }
                if (token.isSym(";")) {
                    i++;
                    continue;
                }
                if (isTypeKeyword(token)) {
                    parseType(annotations, modifiers, typeFqn, null);
                    annotations.clear();
                    modifiers.clear();
                    continue;
                }
                if (token.isIdent("fun")) {
                    methods.add(parseFunction(annotations, modifiers, typeName));
                    annotations.clear();
                    modifiers.clear();
                    continue;
                }
                if (token.isIdent("val") || token.isIdent("var")) {
                    parseProperty(annotations, modifiers, close, fields);
                    annotations.clear();
                    modifiers.clear();
                    continue;
                }
                if (token.isIdent("constructor")) {
                    methods.add(parseSecondaryConstructor(annotations, modifiers, typeName));
                    annotations.clear();
                    modifiers.clear();
                    continue;
                }
                if (token.isIdent("companion")) {
                    i++;
                    if (!eof() && cur().isIdent("object")) {
                        parseType(annotations, modifiers, typeFqn, "Companion");
                    }
                    annotations.clear();
                    modifiers.clear();
                    continue;
                }
                if (token.isIdent("init")) {
                    i++;
                    if (isSym("{")) {
                        i = nextAfterBlock(i);
                    }
                    annotations.clear();
                    modifiers.clear();
                    continue;
                }
                if (token.kind() == KotlinToken.Kind.IDENT && MODIFIERS.contains(token.text())) {
                    modifiers.add(token.text());
                    i++;
                    continue;
                }
                // Anything else is a fragment of a declaration already handled (an accessor, an enum
                // entry, an initializer). Jumping over its brackets keeps it from being read as a
                // member of this class.
                if ((token.isSym("{") || token.isSym("(") || token.isSym("[")) && match[i] > i) {
                    i = match[i] + 1;
                    continue;
                }
                i++;
            }
            i = close + 1;
        }

        private MethodSummary parseFunction(List<AnnotationUse> annotations, List<String> modifiers, String ownerName) {
            int line = cur().line();
            int column = cur().column();
            i++;
            List<String> typeParams = isSym("<") ? readTypeParameters() : List.of();

            int paren = -1;
            int lastLine = line;
            int k = i;
            while (k < tokens.size()) {
                KotlinToken token = tokens.get(k);
                if (token.kind() == KotlinToken.Kind.EOF) {
                    break;
                }
                if (token.isSym("(")) {
                    paren = k;
                    break;
                }
                if (token.isSym("{") || token.isSym("=") || token.isSym("}")) {
                    break;
                }
                if (token.line() > lastLine && startsDeclaration(token)) {
                    break;
                }
                lastLine = token.line();
                k++;
            }

            String name = "";
            for (int j = k - 1; j >= i; j--) {
                if (tokens.get(j).kind() == KotlinToken.Kind.IDENT) {
                    name = tokens.get(j).text();
                    break;
                }
            }

            List<ParameterSummary> params = new ArrayList<>();
            if (paren >= 0 && match[paren] > paren) {
                parseParameters(paren, match[paren], params, null);
                i = match[paren] + 1;
            }
            else {
                i = Math.max(i, k);
            }

            String returnType = null;
            if (isSym(":")) {
                int start = i + 1;
                int end = start;
                int previousLine = cur().line();
                while (end < tokens.size()) {
                    KotlinToken token = tokens.get(end);
                    if (token.kind() == KotlinToken.Kind.EOF) {
                        break;
                    }
                    if (token.isSym("{") || token.isSym("=") || token.isSym("}")) {
                        break;
                    }
                    if (token.isIdent("where")) {
                        break;
                    }
                    if (token.line() > previousLine && startsDeclaration(token)) {
                        break;
                    }
                    previousLine = token.line();
                    end++;
                }
                returnType = render(start, end).trim();
                i = end;
            }
            if (isSym("where")) {
                int k2 = i;
                while (k2 < tokens.size() && !(tokens.get(k2).isSym("{") || tokens.get(k2).isSym("=")
                        || tokens.get(k2).kind() == KotlinToken.Kind.EOF)) {
                    k2++;
                }
                i = k2;
            }
            if (isSym("{")) {
                i = nextAfterBlock(i);
            }
            else if (isSym("=")) {
                i++;
                skipStatement(tokens.size() - 1);
            }
            if (returnType != null && returnType.isEmpty()) {
                returnType = null;
            }
            boolean isPublic = !(modifiers.contains("private") || modifiers.contains("protected")
                    || modifiers.contains("internal"));
            return new MethodSummary(name, returnType, params, annotations, false, isPublic, false, line, column);
        }

        private MethodSummary parseSecondaryConstructor(List<AnnotationUse> annotations, List<String> modifiers,
                String typeName) {
            int line = cur().line();
            int column = cur().column();
            i++;
            List<ParameterSummary> params = new ArrayList<>();
            if (isSym("(") && match[i] > i) {
                parseParameters(i, match[i], params, null);
                i = match[i] + 1;
            }
            if (isSym(":")) {
                i++;
                while (!eof() && !isSym("{") && !isSym("}")) {
                    if ((isSym("(") || isSym("[")) && match[i] > i) {
                        i = match[i] + 1;
                        continue;
                    }
                    i++;
                }
            }
            if (isSym("{")) {
                i = nextAfterBlock(i);
            }
            boolean isPublic = !(modifiers.contains("private") || modifiers.contains("protected")
                    || modifiers.contains("internal"));
            return new MethodSummary(typeName, null, params, annotations, true, isPublic, false, line, column);
        }

        private void parseProperty(List<AnnotationUse> annotations, List<String> modifiers, int limit,
                List<FieldSummary> fields) {
            int line = cur().line();
            int column = cur().column();
            i++;
            if (isSym("(")) {
                // Destructuring declaration: no single name to index.
                if (match[i] > i) {
                    i = match[i] + 1;
                }
                skipStatement(limit);
                return;
            }
            if (eof() || cur().kind() != KotlinToken.Kind.IDENT) {
                skipStatement(limit);
                return;
            }
            String name = cur().text();
            i++;
            String type = null;
            if (isSym(":")) {
                i++;
                type = readTypeText(limit);
            }
            String inferred = null;
            String constant = null;
            if (isSym("=")) {
                i++;
                if (modifiers.contains("const")) {
                    // Read before inferInitializer moves the cursor. The text is kept as written
                    // because a constant may be defined in terms of another one; the resolver folds
                    // the chain after the AST is gone.
                    constant = initializerText(i, limit);
                }
                inferred = inferInitializer(limit);
            }
            else if (isSym("by")) {
                i++;
            }
            skipStatement(limit);
            if (type == null || type.isEmpty()) {
                type = inferred == null ? "" : inferred;
            }
            fields.add(new FieldSummary(name, type, annotations, modifiers.contains("const"), constant, line,
                    column));
        }

        private String readTypeText(int limit) {
            int start = i;
            int end = i;
            int previousLine = cur().line();
            while (end < limit) {
                KotlinToken token = tokens.get(end);
                if (token.isSym("=") || token.isSym(";") || token.isSym("}")) {
                    break;
                }
                if (token.isIdent("by")) {
                    break;
                }
                if (token.line() > previousLine && startsDeclaration(token)) {
                    break;
                }
                if ((token.isSym("(") || token.isSym("[")) && match[end] > end && match[end] < limit) {
                    end = match[end] + 1;
                    continue;
                }
                previousLine = token.line();
                end++;
            }
            i = end;
            return render(start, end).trim();
        }

        /**
         * Types a property from its initializer when no type is written.
         *
         * <p>
         * Only the unambiguous shapes are recognised — a constructor call, a collection factory, a
         * literal. Everything else yields an empty type rather than a guess, because a wrong type
         * here would turn into a wrong dependency edge elsewhere.
         * </p>
         *
         * @param limit end of the enclosing body
         * @return inferred type text, or {@code null}
         */
        private String inferInitializer(int limit) {
            if (eof() || i >= limit) {
                return null;
            }
            KotlinToken first = cur();
            if (first.kind() == KotlinToken.Kind.STRING) {
                return "String";
            }
            if (first.kind() == KotlinToken.Kind.LITERAL) {
                String text = first.text();
                return text.contains(".") ? "Double" : "Int";
            }
            if (first.isIdent("true") || first.isIdent("false")) {
                return "Boolean";
            }
            if (first.kind() != KotlinToken.Kind.IDENT) {
                return null;
            }
            String word = first.text();
            switch (word) {
                case "listOf", "mutableListOf", "arrayListOf", "listOfNotNull":
                    return "List";
                case "setOf", "mutableSetOf", "hashSetOf":
                    return "Set";
                case "mapOf", "mutableMapOf", "hashMapOf":
                    return "Map";
                case "arrayOf":
                    return "Array";
                case "lazy":
                    return null;
                default:
                    break;
            }
            if (!Character.isUpperCase(word.charAt(0))) {
                return null;
            }
            StringBuilder type = new StringBuilder();
            int k = i;
            while (k < limit) {
                KotlinToken token = tokens.get(k);
                if (token.kind() == KotlinToken.Kind.IDENT) {
                    if (type.length() > 0) {
                        type.append('.');
                    }
                    type.append(token.text());
                    k++;
                    if (k < limit && tokens.get(k).isSym(".") && k + 1 < limit
                            && tokens.get(k + 1).kind() == KotlinToken.Kind.IDENT) {
                        k++;
                        continue;
                    }
                    break;
                }
                break;
            }
            return type.length() == 0 ? null : type.toString();
        }

        // ------------------------------------------------------------ parameters

        private void parseParameters(int open, int close, List<ParameterSummary> params, List<FieldSummary> fields) {
            for (int[] segment : splitTopLevel(open + 1, close)) {
                parseParameter(segment[0], segment[1], params, fields);
            }
        }

        /**
         * Splits a token range on commas that are not inside brackets or generic arguments.
         *
         * <p>
         * The generic part is load bearing: splitting {@code JpaRepository<ArticleEntity, Int>} at its
         * comma yields two supertypes, one of which is the nonsense {@code Int>}, and the entity type
         * of every Spring Data repository then comes out {@code null}. Angle brackets are tracked by
         * depth rather than by matching because Kotlin reuses {@code <} and {@code >} for comparison.
         * </p>
         *
         * @param from inclusive start
         * @param to exclusive end
         * @return segment boundaries, in order
         */
        private List<int[]> splitTopLevel(int from, int to) {
            List<int[]> segments = new ArrayList<>();
            int angle = 0;
            int k = from;
            int start = from;
            while (k < to) {
                KotlinToken token = tokens.get(k);
                if ((token.isSym("(") || token.isSym("[") || token.isSym("{")) && match[k] > k) {
                    k = match[k] + 1;
                    continue;
                }
                if (token.isSym("<")) {
                    angle++;
                    k++;
                    continue;
                }
                if (token.isSym(">")) {
                    if (angle > 0) {
                        angle--;
                    }
                    k++;
                    continue;
                }
                if (token.isSym("-") && k + 1 < to && tokens.get(k + 1).isSym(">")) {
                    // A function type's arrow is not the end of a type argument list.
                    k += 2;
                    continue;
                }
                if (token.isSym(",") && angle == 0) {
                    segments.add(new int[] { start, k });
                    start = k + 1;
                    k++;
                    continue;
                }
                k++;
            }
            if (start < to) {
                segments.add(new int[] { start, to });
            }
            return segments;
        }

        /**
         * @param from inclusive start
         * @param to exclusive end
         * @param word a keyword such as {@code where}
         * @return index of the first top-level occurrence, or {@code -1}
         */
        private int indexOfTopLevelWord(int from, int to, String word) {
            int angle = 0;
            int k = from;
            while (k < to) {
                KotlinToken token = tokens.get(k);
                if ((token.isSym("(") || token.isSym("[") || token.isSym("{")) && match[k] > k) {
                    k = match[k] + 1;
                    continue;
                }
                if (token.isSym("<")) {
                    angle++;
                }
                else if (token.isSym(">")) {
                    if (angle > 0) {
                        angle--;
                    }
                }
                else if (angle == 0 && token.isIdent(word)) {
                    return k;
                }
                k++;
            }
            return -1;
        }

        private void parseParameter(int start, int end, List<ParameterSummary> params, List<FieldSummary> fields) {
            int k = start;
            List<AnnotationUse> annotations = new ArrayList<>();
            while (k < end && tokens.get(k).isSym("@")) {
                k = parseAnnotationAt(k, annotations);
            }
            boolean property = false;
            boolean isStatic = false;
            while (k < end && tokens.get(k).kind() == KotlinToken.Kind.IDENT) {
                String word = tokens.get(k).text();
                if ("val".equals(word) || "var".equals(word)) {
                    property = true;
                    k++;
                    continue;
                }
                if ("const".equals(word)) {
                    isStatic = true;
                    k++;
                    continue;
                }
                if (!PARAM_MODIFIERS.contains(word)) {
                    break;
                }
                k++;
            }
            if (k >= end || tokens.get(k).kind() != KotlinToken.Kind.IDENT) {
                return;
            }
            KotlinToken nameToken = tokens.get(k);
            k++;
            String type = "";
            if (k < end && tokens.get(k).isSym(":")) {
                int typeStart = k + 1;
                int typeEnd = end;
                for (int j = typeStart; j < end; j++) {
                    if (tokens.get(j).isSym("=")) {
                        typeEnd = j;
                        break;
                    }
                }
                type = render(typeStart, typeEnd).trim();
            }
            params.add(new ParameterSummary(nameToken.text(), type, annotations));
            if (property && fields != null) {
                fields.add(new FieldSummary(nameToken.text(), type, annotations, isStatic, null, nameToken.line(),
                        nameToken.column()));
            }
        }

        // ------------------------------------------------------------ annotations

        private AnnotationUse parseAnnotation() {
            int line = cur().line();
            i++;
            String name = readQualifiedName();
            if (isSym(":") && i + 1 < tokens.size() && tokens.get(i + 1).kind() == KotlinToken.Kind.IDENT) {
                // Use-site target: @get:JsonProperty means @JsonProperty on the getter.
                i++;
                name = readQualifiedName();
            }
            List<AnnotationArg> args = List.of();
            if (isSym("(") && match[i] > i) {
                args = parseAnnotationArgs(i, match[i]);
                i = match[i] + 1;
            }
            return new AnnotationUse(name, line, args);
        }

        private int parseAnnotationAt(int at, List<AnnotationUse> sink) {
            int saved = i;
            i = at;
            sink.add(parseAnnotation());
            int after = i;
            i = saved;
            return after;
        }

        private List<AnnotationArg> parseAnnotationArgs(int open, int close) {
            List<AnnotationArg> args = new ArrayList<>();
            for (int[] segment : splitTopLevel(open + 1, close)) {
                AnnotationArg arg = annotationArg(segment[0], segment[1]);
                if (arg != null) {
                    args.add(arg);
                }
            }
            return args;
        }

        private AnnotationArg annotationArg(int start, int end) {
            int k = start;
            String key = "value";
            if (k + 1 < end && tokens.get(k).kind() == KotlinToken.Kind.IDENT && tokens.get(k + 1).isSym("=")
                    && !(k + 2 < end && tokens.get(k + 2).isSym("="))) {
                key = tokens.get(k).text();
                k += 2;
            }
            if (k >= end) {
                return null;
            }
            if (k + 1 == end && tokens.get(k).kind() == KotlinToken.Kind.STRING) {
                KotlinToken literal = tokens.get(k);
                return new AnnotationArg(key, List.of(literal.text()), !literal.template());
            }
            if (tokens.get(k).isSym("[") && match[k] == end - 1) {
                return arrayArg(key, k, end - 1);
            }
            // Kotlin writes a single-value array as arrayOf("/x"); treating it as an opaque
            // expression loses a path that is plainly written.
            if (tokens.get(k).isIdent("arrayOf") && k + 1 < end && tokens.get(k + 1).isSym("(")
                    && match[k + 1] == end - 1) {
                return arrayArg(key, k + 1, end - 1);
            }
            return new AnnotationArg(key, List.of(render(k, end).trim()), false);
        }

        private AnnotationArg arrayArg(String key, int open, int close) {
            List<String> values = new ArrayList<>();
            boolean literal = true;
            for (int[] item : splitTopLevel(open + 1, close)) {
                if (item[1] - item[0] == 1 && tokens.get(item[0]).kind() == KotlinToken.Kind.STRING) {
                    KotlinToken literalToken = tokens.get(item[0]);
                    values.add(literalToken.text());
                    if (literalToken.template()) {
                        literal = false;
                    }
                }
                else {
                    values.add(render(item[0], item[1]).trim());
                    literal = false;
                }
            }
            return new AnnotationArg(key, values, literal && !values.isEmpty());
        }

        // ------------------------------------------------------------ supertypes

        private void readSuperTypes(int start, int end, List<String> out) {
            int where = indexOfTopLevelWord(start, end, "where");
            int to = where >= 0 ? where : end;
            for (int[] segment : splitTopLevel(start, to)) {
                String text = superTypeText(segment[0], segment[1]);
                if (!text.isEmpty()) {
                    out.add(text);
                }
            }
        }

        private String superTypeText(int start, int end) {
            int k = start;
            while (k < end && tokens.get(k).isSym("@")) {
                k = skipAnnotation(k);
            }
            int cut = end;
            for (int j = k; j < end; j++) {
                KotlinToken token = tokens.get(j);
                if (token.isSym("(") || token.isIdent("by")) {
                    cut = j;
                    break;
                }
            }
            String text = render(k, cut).trim();
            while (text.endsWith("?")) {
                text = text.substring(0, text.length() - 1).trim();
            }
            return text;
        }

        private int skipAnnotation(int at) {
            int saved = i;
            i = at;
            i++;
            readQualifiedName();
            if (isSym(":") && i + 1 < tokens.size() && tokens.get(i + 1).kind() == KotlinToken.Kind.IDENT) {
                i++;
                readQualifiedName();
            }
            if (isSym("(") && match[i] > i) {
                i = match[i] + 1;
            }
            int after = i;
            i = saved;
            return after;
        }

        // ------------------------------------------------------------ navigation

        /**
         * Finds the {@code {} that opens the type body, or {@code -1} when the declaration has none.
         *
         * <p>
         * The guard against a following declaration is what keeps {@code data class Foo(val id: Long)}
         * without a body from swallowing the next class's brace.
         * </p>
         *
         * @param from index just after the class name and constructor
         * @return index of the opening brace
         */
        private int findBodyStart(int from) {
            int k = from;
            int previousLine = from > 0 ? tokens.get(from - 1).line() : 0;
            while (k < tokens.size()) {
                KotlinToken token = tokens.get(k);
                if (token.kind() == KotlinToken.Kind.EOF) {
                    stoppedAt = k;
                    return -1;
                }
                if (token.isSym("{")) {
                    stoppedAt = k;
                    return k;
                }
                if (token.isSym("}")) {
                    stoppedAt = k;
                    return -1;
                }
                if (token.line() > previousLine && startsDeclaration(token)) {
                    stoppedAt = k;
                    return -1;
                }
                if ((token.isSym("(") || token.isSym("[")) && match[k] > k) {
                    previousLine = tokens.get(match[k]).line();
                    k = match[k] + 1;
                    continue;
                }
                previousLine = token.line();
                k++;
            }
            stoppedAt = tokens.size() - 1;
            return -1;
        }

        private void skipAnonymousObject() {
            if (isSym(":")) {
                i++;
                while (!eof() && !isSym("{") && !isSym("}")) {
                    if ((isSym("(") || isSym("[")) && match[i] > i) {
                        i = match[i] + 1;
                        continue;
                    }
                    i++;
                }
            }
            if (isSym("{")) {
                i = nextAfterBlock(i);
            }
        }

        /**
         * Reads a declaration's type parameter list and steps past it.
         *
         * <p>
         * Only the names are kept: bounds ({@code T : Comparable<T>}) describe what a caller may pass,
         * not what the declaration binds at a use site, and the substitution done later works on the
         * positional names alone. Variance markers and annotations are skipped rather than treated as
         * parameters.
         * </p>
         *
         * @return parameter names in order, e.g. {@code [T, ID]}
         */
        private List<String> readTypeParameters() {
            List<String> names = new ArrayList<>();
            int close = match[i];
            if (close <= i) {
                skipAngles();
                return names;
            }
            int k = i + 1;
            int depth = 0;
            boolean expectName = true;
            while (k < close) {
                KotlinToken token = tokens.get(k);
                if (token.isSym("<")) {
                    depth++;
                    k++;
                    continue;
                }
                if (token.isSym(">")) {
                    depth--;
                    k++;
                    continue;
                }
                if (depth > 0) {
                    k++;
                    continue;
                }
                if (token.isSym(",")) {
                    expectName = true;
                    k++;
                    continue;
                }
                if (token.isSym("@") || token.isIdent("in") || token.isIdent("out") || token.isIdent("reified")) {
                    k++;
                    continue;
                }
                if (expectName && token.kind() == KotlinToken.Kind.IDENT) {
                    names.add(token.text());
                    expectName = false;
                }
                k++;
            }
            i = close + 1;
            return names;
        }

        /**
         * Renders a property initialiser, without moving the cursor.
         *
         * @param from first token of the initialiser
         * @param limit end of the enclosing body
         * @return the expression as written, or {@code null} when there is none
         */
        private String initializerText(int from, int limit) {
            if (from >= limit) {
                return null;
            }
            int end = statementEnd(from, limit);
            String text = render(from, end).trim();
            return text.isEmpty() ? null : text;
        }

        /**
         * Finds where the statement starting at {@code from} ends, mirroring {@link #skipStatement}.
         *
         * @param from first token of the statement
         * @param limit end of the enclosing body
         * @return index of the first token after the statement
         */
        private int statementEnd(int from, int limit) {
            int k = from;
            int startLine = from < tokens.size() ? tokens.get(from).line() : 0;
            while (k < limit) {
                KotlinToken token = tokens.get(k);
                if (token.isSym(";") || token.isSym("}")) {
                    return k;
                }
                if ((token.isSym("{") || token.isSym("(") || token.isSym("[")) && match[k] > k) {
                    k = match[k] + 1;
                    continue;
                }
                if (token.line() > startLine && startsDeclaration(token)) {
                    return k;
                }
                k++;
            }
            return k;
        }

        private void skipAngles() {
            int depth = 0;
            while (!eof()) {
                KotlinToken token = cur();
                if (token.isSym("<")) {
                    depth++;
                    i++;
                    continue;
                }
                if (token.isSym(">")) {
                    depth--;
                    i++;
                    if (depth <= 0) {
                        return;
                    }
                    continue;
                }
                if (token.isSym("-") && i + 1 < tokens.size() && tokens.get(i + 1).isSym(">")) {
                    i += 2;
                    continue;
                }
                if ((token.isSym("(") || token.isSym("[")) && match[i] > i) {
                    i = match[i] + 1;
                    continue;
                }
                i++;
            }
        }

        private int nextAfterBlock(int open) {
            int close = match[open];
            return close > open ? close + 1 : open + 1;
        }

        /**
         * Skips the rest of an expression or statement.
         *
         * <p>
         * Kotlin has no statement terminator, so the end is inferred: a balanced block ends the
         * expression, and a token that can only start a declaration on a later line ends the
         * statement. Without the second rule an initializer would eat the member below it.
         * </p>
         *
         * @param limit index that must not be crossed, normally the end of the enclosing body
         */
        private void skipStatement(int limit) {
            int startLine = cur().line();
            while (i < limit) {
                KotlinToken token = cur();
                if (token.isSym(";")) {
                    i++;
                    return;
                }
                if (token.isSym("}")) {
                    return;
                }
                if ((token.isSym("{") || token.isSym("(") || token.isSym("[")) && match[i] > i) {
                    i = match[i] + 1;
                    continue;
                }
                if (token.line() > startLine && startsDeclaration(token)) {
                    return;
                }
                i++;
            }
        }

        private boolean startsDeclaration(KotlinToken token) {
            if (token.kind() == KotlinToken.Kind.EOF) {
                return true;
            }
            if (token.isSym("@")) {
                return true;
            }
            return token.kind() == KotlinToken.Kind.IDENT && DECLARATION_WORDS.contains(token.text());
        }

        private static boolean isTypeKeyword(KotlinToken token) {
            return token.isIdent("class") || token.isIdent("interface") || token.isIdent("object");
        }

        /**
         * Reads {@code a.b.C}. Continuation requires a dot: a bare identifier on the next line is a
         * new declaration, not another segment of this name.
         *
         * @return the dotted name, empty when the cursor is not on an identifier
         */
        private String readQualifiedName() {
            StringBuilder name = new StringBuilder();
            if (eof() || cur().kind() != KotlinToken.Kind.IDENT) {
                return "";
            }
            name.append(cur().text());
            i++;
            while (!eof() && cur().isSym(".") && i + 1 < tokens.size()
                    && tokens.get(i + 1).kind() == KotlinToken.Kind.IDENT) {
                i++;
                name.append('.').append(cur().text());
                i++;
            }
            return name.toString();
        }

        private String render(int from, int to) {
            StringBuilder out = new StringBuilder();
            KotlinToken previous = null;
            for (int k = from; k < to; k++) {
                KotlinToken token = tokens.get(k);
                if (previous != null && spaceBetween(previous, token)) {
                    out.append(' ');
                }
                out.append(display(token));
                previous = token;
            }
            return out.toString();
        }

        private static String display(KotlinToken token) {
            return token.kind() == KotlinToken.Kind.STRING ? "\"" + token.text() + "\"" : token.text();
        }

        private static boolean spaceBetween(KotlinToken previous, KotlinToken next) {
            String left = previous.kind() == KotlinToken.Kind.STRING ? "" : previous.text();
            String right = next.kind() == KotlinToken.Kind.STRING ? "" : next.text();
            return !TIGHT_BEFORE.contains(right) && !TIGHT_AFTER.contains(left);
        }

        private boolean eof() {
            return i >= tokens.size() || tokens.get(i).kind() == KotlinToken.Kind.EOF;
        }

        private KotlinToken cur() {
            return tokens.get(Math.min(i, tokens.size() - 1));
        }

        private boolean isSym(String symbol) {
            return !eof() && cur().isSym(symbol);
        }



    }
}
