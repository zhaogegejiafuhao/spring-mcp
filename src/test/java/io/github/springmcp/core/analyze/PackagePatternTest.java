package io.github.springmcp.core.analyze;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Coverage rules for {@code @MapperScan} package patterns.
 *
 * <p>
 * Found on {@code yangzongzhuan/RuoYi}: the project declares {@code @MapperScan("com.ruoyi.**.mapper")},
 * and plain prefix matching against that string matches nothing. Every mapper interface was therefore
 * invisible, and its XML was reported as an orphaned namespace — a wrong answer that reads like a
 * finding about the project rather than a limitation of the tool.
 * </p>
 */
class PackagePatternTest {

    @Test
    @DisplayName("** spans zero or more whole segments, as MyBatis resolves it")
    void doubleStarSpansSegments() {
        String pattern = "com.ruoyi.**.mapper";

        assertThat(MapperAnalyzer.matchesScannedPackage(pattern, "com.ruoyi.mapper")).isTrue();
        assertThat(MapperAnalyzer.matchesScannedPackage(pattern, "com.ruoyi.system.mapper")).isTrue();
        assertThat(MapperAnalyzer.matchesScannedPackage(pattern, "com.ruoyi.a.b.mapper")).isTrue();
    }

    @Test
    @DisplayName("** does not leak outside the literal prefix")
    void doubleStarDoesNotOverreach() {
        String pattern = "com.ruoyi.**.mapper";

        assertThat(MapperAnalyzer.matchesScannedPackage(pattern, "com.ruoyi.system.mapperx")).isFalse();
        assertThat(MapperAnalyzer.matchesScannedPackage(pattern, "com.ruoyi.system.mapper.sub")).isFalse();
        assertThat(MapperAnalyzer.matchesScannedPackage(pattern, "com.other.mapper")).isFalse();
        assertThat(MapperAnalyzer.matchesScannedPackage(pattern, "com.ruoyi2.system.mapper")).isFalse();
    }

    @Test
    @DisplayName("a single * spans exactly one segment")
    void singleStarSpansOneSegment() {
        assertThat(MapperAnalyzer.matchesScannedPackage("com.acme.*.mapper", "com.acme.system.mapper")).isTrue();
        assertThat(MapperAnalyzer.matchesScannedPackage("com.acme.*.mapper", "com.acme.a.b.mapper")).isFalse();
        assertThat(MapperAnalyzer.matchesScannedPackage("com.acme.*.mapper", "com.acme.mapper")).isFalse();
        assertThat(MapperAnalyzer.matchesScannedPackage("com.acme.*", "com.acme.mapper")).isTrue();
    }

    @Test
    @DisplayName("a pattern without wildcards still covers sub-packages, as before")
    void plainPackageKeepsPrefixSemantics() {
        assertThat(MapperAnalyzer.matchesScannedPackage("com.acme.scanned", "com.acme.scanned")).isTrue();
        assertThat(MapperAnalyzer.matchesScannedPackage("com.acme.scanned", "com.acme.scanned.inner")).isTrue();
        assertThat(MapperAnalyzer.matchesScannedPackage("com.acme.scanned", "com.acme.scannedx")).isFalse();
    }
}
