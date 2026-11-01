package io.github.springmcp.core.analyze;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;

import io.github.springmcp.core.index.CuSummary;
import io.github.springmcp.core.index.FieldSummary;
import io.github.springmcp.core.index.MethodSummary;
import io.github.springmcp.core.index.ParameterSummary;
import io.github.springmcp.core.index.TypeSummary;
import io.github.springmcp.core.kotlin.KotlinCallScanner;
import io.github.springmcp.core.parse.AstCache;
import io.github.springmcp.core.resolve.Resolution;
import io.github.springmcp.core.resolve.TypeNameResolver;
import io.github.springmcp.core.util.AstNodes;
import io.github.springmcp.core.util.TypeFqns;
import io.github.springmcp.spi.ErrorCode;
import io.github.springmcp.spi.ToolFailure;
import io.github.springmcp.spi.model.Confidence;
import io.github.springmcp.spi.model.ResolutionRule;
import io.github.springmcp.spi.model.ServiceCalls;

/**
 * Finds the direct call sites of a service class or method.
 *
 * <p>
 * Two phases, and the split is what makes this usable on a real repository. Phase A works purely on
 * the pre-computed summaries: a file can only contain a relevant call if the method name appears in
 * its {@code calledMethodNames} set, which usually narrows thousands of files down to a few dozen.
 * Phase B parses only those files and does the expensive receiver resolution.
 * </p>
 *
 * <p>
 * A call chain is <em>not</em> followed. Asking for {@code OrderService} gives the places that call
 * it, not the controllers above them; the caller can walk one hop at a time with the returned
 * caller types, which keeps every answer verifiable.
 * </p>
 */
public final class ServiceCallerAnalyzer {

    private final Map<String, TypeSummary> byFqn;

    private final Map<Path, CuSummary> byFile;

    private final List<TypeSummary> allTypes;

    private final TypeNameResolver resolver;

    private final SuperTypeLite superTypes;

    private final ReceiverResolver receiverResolver;

    private final AstCache astCache;

    /**
     * Narrow view of the supertype graph, so this analyzer can be tested with a stand-in.
     *
     * @param isAssignableTo subtype check
     */
    public interface SuperTypeLite {

        boolean isAssignableTo(String candidate, String target);

    }

    /**
     * @param byFqn every type keyed by fully qualified name
     * @param byFile every file summary
     * @param allTypes every type
     * @param resolver name resolver
     * @param superTypes subtype checker
     * @param receiverResolver receiver type resolver
     * @param astCache cache used to fetch ASTs for candidate files only
     */
    public ServiceCallerAnalyzer(Map<String, TypeSummary> byFqn, Map<Path, CuSummary> byFile,
            List<TypeSummary> allTypes, TypeNameResolver resolver, SuperTypeLite superTypes,
            ReceiverResolver receiverResolver, AstCache astCache) {
        this.byFqn = byFqn;
        this.byFile = byFile;
        this.allTypes = allTypes;
        this.resolver = resolver;
        this.superTypes = superTypes;
        this.receiverResolver = receiverResolver;
        this.astCache = astCache;
    }

    /**
     * Runs the analysis over every file.
     * @param query target and filters
     * @return the report
     * @throws ToolFailure when the target class cannot be resolved or is ambiguous
     */
    public ServiceCalls.Report analyze(ServiceCalls.Query query) throws ToolFailure {
        return analyze(query, cu -> true);
    }

    /**
     * Runs the analysis over a subset of the files.
     *
     * <p>
     * The target class itself is still resolved against the whole project — only the places that
     * might <em>call</em> it are restricted. Skipping the AST scan of excluded files is also where
     * scoping pays for itself: on a large repository most of the cost is parsing candidate files.
     * </p>
     *
     * @param query target and filters
     * @param fileInScope which files may contribute call sites
     * @return the report
     * @throws ToolFailure when the target class cannot be resolved or is ambiguous
     */
    public ServiceCalls.Report analyze(ServiceCalls.Query query, Predicate<CuSummary> fileInScope)
            throws ToolFailure {
        Resolution target = resolver.resolveGlobally(query.serviceClass());
        if (target.status() == Resolution.Status.AMBIGUOUS) {
            throw ToolFailure.of(ErrorCode.AMBIGUOUS_CLASS,
                    "类名 '" + query.serviceClass() + "' 匹配到多个类: " + String.join(", ", target.candidates()),
                    "请改用全限定名重试");
        }
        if (!target.hasFqn()) {
            throw ToolFailure.of(ErrorCode.CLASS_NOT_FOUND, "找不到类: " + query.serviceClass(),
                    "确认类名拼写，或提供全限定名");
        }

        String targetFqn = target.fqn();
        String targetSimple = targetFqn.substring(targetFqn.lastIndexOf('.') + 1);
        String methodName = query.methodName();
        int limit = query.limit() > 0 ? query.limit() : 500;

        List<CuSummary> candidates = prefilter(methodName, targetSimple, fileInScope);
        // Two front ends, one report: Java call sites come from the AST, Kotlin ones from the token
        // stream. The receiver rules differ in how they are computed, not in what they mean.
        List<CuSummary> javaCandidates = new ArrayList<>(candidates.size());
        List<CuSummary> kotlinCandidates = new ArrayList<>(0);
        for (CuSummary cu : candidates) {
            if (cu.file().toString().endsWith(".kt")) {
                kotlinCandidates.add(cu);
            }
            else {
                javaCandidates.add(cu);
            }
        }

        List<ServiceCalls.CallSite> callSites = new ArrayList<>();
        List<ServiceCalls.UnresolvedCallSite> unresolved = new ArrayList<>();
        Set<String> dedupe = new LinkedHashSet<>();
        boolean[] truncated = { false };

        for (CuSummary cu : javaCandidates) {
            CompilationUnit ast;
            try {
                ast = astCache.obtain(cu.file(), cu.lastModifiedMillis());
            }
            catch (Exception e) {
                unresolved.add(new ServiceCalls.UnresolvedCallSite(null, null, cu.file().toString(), 0, "",
                        "PARSE_FAILED", List.of()));
                continue;
            }
            if (ast == null) {
                continue;
            }

            for (MethodCallExpr call : ast.findAll(MethodCallExpr.class)) {
                if (methodName != null && !call.getNameAsString().equals(methodName)) {
                    continue;
                }
                ReceiverResolver.Result receiver = receiverResolver.resolve(call, cu, call.getNameAsString(),
                        query.includeLowConfidence());

                if (!receiver.resolved()) {
                    if (query.includeUnresolved() && looksLikeTarget(receiver.expression(), targetSimple)) {
                        unresolved.add(new ServiceCalls.UnresolvedCallSite(callerType(call, cu),
                                callerMethod(call), cu.file().toString(), line(call), receiver.expression(),
                                receiver.unresolvedReason(), receiver.candidates()));
                    }
                    continue;
                }

                Resolution receiverType = resolver.resolve(receiver.typeRaw(), cu);
                if (!receiverType.hasFqn()) {
                    continue;
                }
                String receiverFqn = receiverType.fqn();

                ServiceCalls.MatchKind matchKind = classify(receiverFqn, targetFqn);
                if (matchKind == null) {
                    continue;
                }
                // "Self call" means a call made from inside the target type, not a call whose
                // receiver happens to be the target. The distinction matters: treating every call to
                // the service as a self call made includeSelfCalls=false return almost nothing.
                String callerFqn = callerType(call, cu);
                boolean selfCall = receiverFqn.equals(targetFqn) && targetFqn.equals(callerFqn);
                if (selfCall && !query.includeSelfCalls()) {
                    continue;
                }
                if (receiver.rule() == ResolutionRule.NAME_CONVENTION && !query.includeLowConfidence()) {
                    continue;
                }

                String key = cu.file() + ":" + line(call) + ":" + call.getBegin().map(p -> p.column).orElse(0) + ":"
                        + call.getNameAsString();
                if (!dedupe.add(key)) {
                    continue;
                }
                if (callSites.size() >= limit) {
                    truncated[0] = true;
                    continue;
                }

                callSites.add(new ServiceCalls.CallSite(callerFqn, callerMethod(call),
                        callerSignature(call), cu.file().toString(), line(call),
                        call.getBegin().map(p -> p.column).orElse(0), receiver.expression(), receiverFqn,
                        receiver.declaredIn(), receiver.rule(), matchKind, confidenceOf(receiver.rule()),
                        snippet(cu.file(), line(call))));
            }
        }

        int kotlinHits = 0;
        for (CuSummary cu : kotlinCandidates) {
            kotlinHits += kotlinCallSites(cu, query, targetFqn, targetSimple, callSites, unresolved, dedupe, truncated,
                    limit, methodName);
        }

        callSites.sort(Comparator.comparing(ServiceCalls.CallSite::file)
            .thenComparingInt(ServiceCalls.CallSite::line));

        List<String> warnings = new ArrayList<>();
        if (truncated[0]) {
            warnings.add("结果已按 limit=" + limit + " 截断，请缩小 methodName 或提高 limit");
        }
        if (!query.includeLowConfidence()) {
            warnings.add("已排除命名约定推断（低置信度）的调用点；需要时设 includeLowConfidence=true");
        }
        if (!kotlinCandidates.isEmpty() && kotlinHits == 0) {
            warnings.add("扫描了 " + kotlinCandidates.size()
                    + " 个 Kotlin 候选文件，未定位到调用点：Kotlin 只认接收者能静态定型的调用（字段、参数、同函数内的局部 val）");
        }

        return new ServiceCalls.Report(targetFqn, target.candidates(), callSites.size() + unresolved.size(),
                callSites.size(), unresolved.size(), truncated[0], callSites, unresolved, warnings);
    }

    /**
     * Phase A: keep only files that can possibly contain a relevant call.
     */
    private List<CuSummary> prefilter(String methodName, String targetSimple, Predicate<CuSummary> fileInScope) {
        List<CuSummary> out = new ArrayList<>();
        for (CuSummary cu : byFile.values()) {
            if (!fileInScope.test(cu)) {
                continue;
            }
            if (methodName != null) {
                if (cu.calledMethodNames().contains(methodName)) {
                    out.add(cu);
                }
            }
            else if (cu.referencedSimpleTypeNames().contains(targetSimple)) {
                out.add(cu);
            }
        }
        out.sort(Comparator.comparing(c -> c.file().toString()));
        return out;
    }

    /**
     * Kotlin half of phase B.
     *
     * <p>
     * The Java side asks JavaParser for the receiver expression and walks the AST to type it. Kotlin
     * has no AST here, so {@link KotlinCallScanner} reports the receiver and, when it is a local, the
     * type written next to it. Everything else is typed from the enclosing type's fields and the
     * enclosing method's parameters — the same rules the Java resolver applies, minus the ones that
     * would need a type environment.
     * </p>
     *
     * @return number of resolved call sites added
     */
    private int kotlinCallSites(CuSummary cu, ServiceCalls.Query query, String targetFqn, String targetSimple,
            List<ServiceCalls.CallSite> callSites, List<ServiceCalls.UnresolvedCallSite> unresolved,
            Set<String> dedupe, boolean[] truncated, int limit, String methodName) {
        int hits = 0;
        for (KotlinCallScanner.Call call : KotlinCallScanner.scan(cu.file())) {
            if (methodName != null && !call.method().equals(methodName)) {
                continue;
            }
            TypeSummary owner = typeOf(cu, call.enclosingType());
            String callerFqn = owner == null ? "" : owner.fqn();
            KotlinReceiver receiver = kotlinReceiver(call, owner);

            if (receiver == null) {
                if (query.includeUnresolved()) {
                    String expression = call.receiver() + "." + call.method() + "()";
                    if (looksLikeTarget(expression, targetSimple)) {
                        unresolved.add(new ServiceCalls.UnresolvedCallSite(callerFqn, call.enclosingMethod(),
                                cu.file().toString(), call.line(), expression, "UNKNOWN_RECEIVER", List.of()));
                    }
                }
                continue;
            }
            Resolution resolution = resolver.resolve(receiver.typeRaw(), cu);
            if (!resolution.hasFqn()) {
                continue;
            }
            ServiceCalls.MatchKind kind = classify(resolution.fqn(), targetFqn);
            if (kind == null) {
                continue;
            }
            boolean selfCall = resolution.fqn().equals(targetFqn) && targetFqn.equals(callerFqn);
            if (selfCall && !query.includeSelfCalls()) {
                continue;
            }
            String key = cu.file() + ":" + call.line() + ":" + call.column() + ":" + call.method();
            if (!dedupe.add(key)) {
                continue;
            }
            if (callSites.size() >= limit) {
                truncated[0] = true;
                continue;
            }
            callSites.add(new ServiceCalls.CallSite(callerFqn, call.enclosingMethod(),
                    signature(owner, call.enclosingMethod()), cu.file().toString(), call.line(), call.column(),
                    call.receiver(), resolution.fqn(), callerFqn, receiver.rule(), kind,
                    confidenceOf(receiver.rule()), snippet(cu.file(), call.line())));
            hits++;
        }
        return hits;
    }

    /**
     * @param typeRaw declared type of the receiver
     * @param rule how the type was found, reported to the caller for the same reason the Java side
     * reports it
     */
    private record KotlinReceiver(String typeRaw, ResolutionRule rule) {
    }

    /**
     * Types the receiver of a Kotlin call.
     *
     * <p>
     * Order matters and mirrors the Java resolver: a local declaration is the nearest binding, then a
     * field, then a parameter, then a class name. Returning {@code null} is a real answer — it means
     * "this scanner cannot tell", which the caller reports as unresolved instead of guessing.
     * </p>
     *
     * @param call the call site
     * @param owner enclosing type, may be {@code null} when the file is unusual
     * @return the receiver type and how it was found, or {@code null}
     */
    private static KotlinReceiver kotlinReceiver(KotlinCallScanner.Call call, TypeSummary owner) {
        if (call.localType() != null && !call.localType().isBlank()) {
            return new KotlinReceiver(call.localType(), ResolutionRule.LOCAL_VAR);
        }
        if ("this".equals(call.receiver())) {
            return owner == null ? null : new KotlinReceiver(owner.fqn(), ResolutionRule.IMPLICIT_THIS);
        }
        if (owner != null) {
            FieldSummary field = owner.field(call.receiver());
            if (field != null && !field.typeRaw().isBlank()) {
                return new KotlinReceiver(field.typeRaw(), ResolutionRule.FIELD);
            }
            MethodSummary method = owner.method(call.enclosingMethod());
            if (method != null) {
                for (ParameterSummary parameter : method.parameters()) {
                    if (parameter.name().equals(call.receiver()) && !parameter.typeRaw().isBlank()) {
                        return new KotlinReceiver(parameter.typeRaw(), ResolutionRule.METHOD_PARAM);
                    }
                }
            }
        }
        if (!call.receiver().isEmpty() && Character.isUpperCase(call.receiver().charAt(0))) {
            return new KotlinReceiver(call.receiver(), ResolutionRule.STATIC_CLASS);
        }
        return null;
    }

    private static TypeSummary typeOf(CuSummary cu, String simpleName) {
        if (simpleName.isEmpty()) {
            return null;
        }
        for (TypeSummary type : cu.types()) {
            if (type.simpleName().equals(simpleName)) {
                return type;
            }
        }
        return null;
    }

    private static String signature(TypeSummary owner, String methodName) {
        if (owner == null || methodName.isEmpty()) {
            return "";
        }
        MethodSummary method = owner.method(methodName);
        return method == null ? methodName + "()" : method.signature();
    }

    private ServiceCalls.MatchKind classify(String receiverFqn, String targetFqn) {
        if (receiverFqn.equals(targetFqn)) {
            return ServiceCalls.MatchKind.EXACT;
        }
        if (superTypes.isAssignableTo(receiverFqn, targetFqn)) {
            return ServiceCalls.MatchKind.IMPLEMENTATION;
        }
        if (superTypes.isAssignableTo(targetFqn, receiverFqn)) {
            return ServiceCalls.MatchKind.INTERFACE_OF_IMPL;
        }
        return null;
    }

    private static Confidence confidenceOf(ResolutionRule rule) {
        return rule == ResolutionRule.NAME_CONVENTION ? Confidence.LOW : Confidence.HIGH;
    }

    /**
     * Heuristic for keeping the unresolved list relevant: an expression mentioning the target's
     * name is worth reporting, an arbitrary {@code foo.bar()} is not.
     */
    private static boolean looksLikeTarget(String expression, String targetSimple) {
        if (expression == null) {
            return false;
        }
        String lower = expression.toLowerCase(Locale.ROOT);
        return lower.contains(targetSimple.toLowerCase(Locale.ROOT));
    }

    private String callerType(MethodCallExpr call, CuSummary cu) {
        return AstNodes.enclosingType(call).map(td -> TypeFqns.of(td, cu.packageName())).orElse("");
    }

    private static String callerMethod(MethodCallExpr call) {
        var md = AstNodes.enclosingMethod(call);
        if (md.isPresent()) {
            return md.get().getNameAsString();
        }
        var cd = AstNodes.enclosingConstructor(call);
        if (cd.isPresent()) {
            return "<init>";
        }
        return "<initializer>";
    }

    private static String callerSignature(MethodCallExpr call) {
        var md = AstNodes.enclosingMethod(call);
        if (md.isPresent()) {
            StringBuilder sb = new StringBuilder(md.get().getNameAsString()).append('(');
            for (int i = 0; i < md.get().getParameters().size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(md.get().getParameter(i).getType().asString());
            }
            return sb.append(") : ").append(md.get().getType().asString()).toString();
        }
        var cd = AstNodes.enclosingConstructor(call);
        if (cd.isPresent()) {
            return cd.get().getNameAsString() + "()";
        }
        return "";
    }

    private static int line(MethodCallExpr call) {
        return call.getBegin().map(p -> p.line).orElse(0);
    }

    private static String snippet(Path file, int line) {
        try {
            List<String> lines = java.nio.file.Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8);
            if (line >= 1 && line <= lines.size()) {
                String s = lines.get(line - 1).trim();
                return s.length() <= 200 ? s : s.substring(0, 200) + "...";
            }
        }
        catch (Exception e) {
            // Snippet is cosmetic; never fail the call over it.
        }
        return "";
    }

    /**
     * Builds a receiver resolver backed by this analyzer's view of the project.
     * @param byFqn types by FQN
     * @param resolver name resolver
     * @param closureOf supertype closure function
     * @return resolver
     */
    public static ReceiverResolver receiverResolver(Map<String, TypeSummary> byFqn, TypeNameResolver resolver,
            java.util.function.Function<String, Set<String>> closureOf) {
        return new ReceiverResolver(byFqn, resolver, new ReceiverResolver.SuperTypeGraphHolder(closureOf));
    }
}
