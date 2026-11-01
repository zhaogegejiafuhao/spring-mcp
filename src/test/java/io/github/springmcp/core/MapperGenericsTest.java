package io.github.springmcp.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.springmcp.spi.api.IndexOptions;
import io.github.springmcp.spi.model.Mappers;

/**
 * The entity type of a repository must survive an intermediate interface.
 *
 * <p>
 * {@code interface CoinDao extends BaseDao<Coin>} with {@code interface BaseDao<T> extends
 * JpaRepository<T, Long>} is how teams share CRUD plumbing. Reading only the direct {@code extends}
 * clause reported {@code entityType=null} for every such repository — 82 mappers in the regression
 * corpus, 54 of 67 in one project — which reads as "this repository has no entity" rather than "the
 * answer is one hop away".
 * </p>
 */
class MapperGenericsTest {

    private static void write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static Mappers.Descriptor descriptor(Mappers.Report report, String simpleName) {
        return report.mappers().stream()
            .filter(d -> d.simpleName().equals(simpleName))
            .findFirst()
            .orElseThrow(() -> new AssertionError(simpleName + " not reported: " + report.mappers()));
    }

    @Test
    @DisplayName("an entity bound in a direct generic base is resolved")
    void oneHop(@TempDir Path root) throws Exception {
        write(root, "src/main/java/m/Coin.java", "package m; public class Coin {}");
        write(root, "src/main/java/m/BaseDao.java", """
                package m;

                import org.springframework.data.jpa.repository.JpaRepository;

                public interface BaseDao<T> extends JpaRepository<T, Long> {
                }
                """);
        write(root, "src/main/java/m/CoinDao.java", """
                package m;

                public interface CoinDao extends BaseDao<Coin> {
                }
                """);

        Mappers.Report report = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000)
            .open(root, IndexOptions.defaults())
            .mappers(new Mappers.Query(Set.of(), null));

        Mappers.Descriptor coinDao = descriptor(report, "CoinDao");
        assertThat(coinDao.kind()).isEqualTo(Mappers.Kind.JPA);
        assertThat(coinDao.entityType()).isEqualTo("Coin");
        assertThat(coinDao.idType()).isEqualTo("Long");
    }

    @Test
    @DisplayName("a two-level chain substitutes through both type parameters")
    void twoHops(@TempDir Path root) throws Exception {
        write(root, "src/main/java/m/User.java", "package m; public class User {}");
        write(root, "src/main/java/m/BaseDao.java", """
                package m;

                import org.springframework.data.jpa.repository.JpaRepository;

                public interface BaseDao<T, ID> extends JpaRepository<T, ID> {
                }
                """);
        write(root, "src/main/java/m/AuditedDao.java", """
                package m;

                public interface AuditedDao<T> extends BaseDao<T, Long> {
                }
                """);
        write(root, "src/main/java/m/UserDao.java", """
                package m;

                public interface UserDao extends AuditedDao<User> {
                }
                """);

        Mappers.Report report = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000)
            .open(root, IndexOptions.defaults())
            .mappers(new Mappers.Query(Set.of(), null));

        Mappers.Descriptor userDao = descriptor(report, "UserDao");
        assertThat(userDao.entityType()).isEqualTo("User");
        assertThat(userDao.idType()).isEqualTo("Long");
    }

    @Test
    @DisplayName("a base whose own parameter is unbound still reports no entity")
    void abstractBaseStaysUnknown(@TempDir Path root) throws Exception {
        write(root, "src/main/java/m/BaseDao.java", """
                package m;

                import org.springframework.data.jpa.repository.JpaRepository;

                public interface BaseDao<T> extends JpaRepository<T, Long> {
                }
                """);

        Mappers.Report report = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000)
            .open(root, IndexOptions.defaults())
            .mappers(new Mappers.Query(Set.of(), null));

        // T is a parameter, not an entity: reporting "T" would look resolved and mean nothing.
        assertThat(descriptor(report, "BaseDao").entityType()).isNull();
    }

    @Test
    @DisplayName("an intermediate whose name resolves in the file's own package is followed")
    void samePackageIntermediate(@TempDir Path root) throws Exception {
        write(root, "src/main/java/m/Thing.java", "package m; public class Thing {}");
        write(root, "src/main/java/m/BaseDao.java", """
                package m;

                import org.springframework.data.jpa.repository.JpaRepository;

                public interface BaseDao<T> extends JpaRepository<T, Long> {
                }
                """);
        // A same-named interface in a different package must not win: the file sits in m, so `BaseDao`
        // is m.BaseDao without any import.
        write(root, "src/main/java/m2/BaseDao.java", """
                package m2;

                import org.springframework.data.jpa.repository.JpaRepository;

                public interface BaseDao<T> extends JpaRepository<T, Long> {
                }
                """);
        write(root, "src/main/java/m/ThingDao.java", """
                package m;

                public interface ThingDao extends BaseDao<Thing> {
                }
                """);

        Mappers.Report report = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000)
            .open(root, IndexOptions.defaults())
            .mappers(new Mappers.Query(Set.of(), null));

        assertThat(descriptor(report, "ThingDao").entityType()).isEqualTo("Thing");
    }

    @Test
    @DisplayName("an intermediate in another package is followed through its import")
    void importedIntermediate(@TempDir Path root) throws Exception {
        write(root, "src/main/java/m/Order.java", "package m; public class Order {}");
        write(root, "src/main/java/shared/CommonDao.java", """
                package shared;

                import org.springframework.data.jpa.repository.JpaRepository;

                public interface CommonDao<T, ID> extends JpaRepository<T, ID> {
                }
                """);
        write(root, "src/main/java/m/OrderDao.java", """
                package m;

                import shared.CommonDao;

                public interface OrderDao extends CommonDao<Order, Long> {
                }
                """);

        Mappers.Report report = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000)
            .open(root, IndexOptions.defaults())
            .mappers(new Mappers.Query(Set.of(), null));

        Mappers.Descriptor orderDao = descriptor(report, "OrderDao");
        assertThat(orderDao.entityType()).isEqualTo("Order");
        assertThat(orderDao.idType()).isEqualTo("Long");
    }
}
