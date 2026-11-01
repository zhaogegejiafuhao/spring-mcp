package io.github.springmcp.server;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The configured projects, in command line order.
 *
 * <p>
 * Immutable and tiny; its job is to answer three questions: what is the default root, what does an
 * alias mean, and which aliases can be advertised to the caller. Lookups are case-insensitive
 * because a tool argument typed by an LLM will not reliably preserve casing.
 * </p>
 */
public final class ProjectCatalog {

    private static final ProjectCatalog EMPTY = new ProjectCatalog(List.of());

    private final List<ProjectAlias> projects;

    private final Map<String, ProjectAlias> byLowerName;

    private ProjectCatalog(List<ProjectAlias> projects) {
        this.projects = List.copyOf(projects);
        Map<String, ProjectAlias> index = new LinkedHashMap<>();
        for (ProjectAlias project : projects) {
            index.putIfAbsent(project.name().toLowerCase(Locale.ROOT), project);
        }
        this.byLowerName = Map.copyOf(index);
    }

    /**
     * @param projects configured projects, first one is the default
     * @return catalog
     */
    public static ProjectCatalog of(List<ProjectAlias> projects) {
        return projects == null || projects.isEmpty() ? EMPTY : new ProjectCatalog(projects);
    }

    /**
     * @param root single project root, exposed under the alias {@code default}
     * @return catalog with one entry
     */
    public static ProjectCatalog single(Path root) {
        return root == null ? EMPTY : new ProjectCatalog(List.of(new ProjectAlias("default", root)));
    }

    /** @return catalog with no projects */
    public static ProjectCatalog empty() {
        return EMPTY;
    }

    /** @return whether nothing is configured */
    public boolean isEmpty() {
        return projects.isEmpty();
    }

    /** @return all projects in configuration order */
    public List<ProjectAlias> all() {
        return projects;
    }

    /** @return alias names in configuration order */
    public List<String> names() {
        return projects.stream().map(ProjectAlias::name).toList();
    }

    /** @return the default project root, or {@code null} when nothing is configured */
    public Path defaultRoot() {
        return projects.isEmpty() ? null : projects.get(0).root();
    }

    /** @return the default alias name, or {@code null} */
    public String defaultName() {
        return projects.isEmpty() ? null : projects.get(0).name();
    }

    /**
     * Looks up an alias.
     * @param name alias, matched case-insensitively
     * @return the project, or empty when unknown
     */
    public Optional<ProjectAlias> byAlias(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byLowerName.get(name.trim().toLowerCase(Locale.ROOT)));
    }

    /**
     * Renders the available aliases for an error message.
     * @return e.g. {@code "petclinic, order-service"} or {@code "(未配置任何工程)"}
     */
    public String describeAvailable() {
        return projects.isEmpty() ? "(未配置任何工程，可用 --project=<dir> 指定)"
                : String.join(", ", names());
    }

    /**
     * Adds a project, renaming on collision.
     *
     * <p>
     * Unnamed {@code --project=/path} arguments take their folder name as the alias, which is what a
     * user would have called it anyway. Two folders named {@code app} would collide, so the second
     * becomes {@code app-2} rather than silently overwriting the first.
     * </p>
     * @param name desired alias
     * @param root project root
     * @return a new catalog
     */
    public ProjectCatalog with(String name, Path root) {
        List<ProjectAlias> merged = new ArrayList<>(projects);
        String candidate = name;
        int suffix = 2;
        while (byAlias(candidate).isPresent()) {
            candidate = name + "-" + suffix++;
        }
        merged.add(new ProjectAlias(candidate, root));
        return new ProjectCatalog(merged);
    }
}
