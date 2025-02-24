package sorald.miner;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonar.api.rules.RuleType;
import sorald.*;
import sorald.cli.SoraldVersionProvider;
import sorald.event.StatsMetadataKeys;
import sorald.processor.CastArithmeticOperandProcessor;
import sorald.rule.IRuleType;
import sorald.rule.Rule;
import sorald.rule.RuleProvider;
import sorald.sonar.SonarRuleType;

public class WarningMinerTest {

    private static final Path REPOS_TXT =
            TestHelper.PATH_TO_RESOURCES_FOLDER.resolve("warning_miner").resolve("test_repos.txt");
    private static final Path EXPECTED_OUTPUT_TXT =
            TestHelper.PATH_TO_RESOURCES_FOLDER
                    .resolve("warning_miner")
                    .resolve("test_results.txt");

    @Test
    public void test_warningMiner() throws Exception {
        File outputFile = File.createTempFile("warnings", null),
                temp = Files.createTempDirectory("tempDir").toFile();

        runMiner(REPOS_TXT, outputFile.getPath(), temp.getPath());

        List<String> expectedLines = extractSortedNonZeroChecks(EXPECTED_OUTPUT_TXT);
        List<String> actualLines = extractSortedNonZeroChecks(outputFile.toPath());

        assertFalse(expectedLines.isEmpty(), "sanity check failure, expected output is empty");
        assertThat(actualLines, equalTo(expectedLines));
    }

    @Test
    public void test_onlyMineRepairableViolations() throws Exception {
        File outputFile = File.createTempFile("warnings", null),
                temp = Files.createTempDirectory("tempDir").toFile();

        runMiner(REPOS_TXT, outputFile.getPath(), temp.getPath(), Constants.ARG_HANDLED_RULES);

        List<String> actualLines = extractSortedNonZeroChecks(outputFile.toPath());
        List<String> expectedLines = extractSortedNonZeroChecks(EXPECTED_OUTPUT_TXT);

        expectedLines =
                expectedLines.stream()
                        .filter(
                                line -> {
                                    String ruleKey = line.split("=")[0];
                                    return Processors.getProcessor(ruleKey) != null;
                                })
                        .collect(Collectors.toList());

        assertThat(actualLines, equalTo(expectedLines));
    }

    /**
     * Test that the warnings miner respects the --ruleTypes option, and only uses checks of that
     * type when given.
     */
    @Test
    public void warningsMiner_onlyScansForGivenTypes_whenRuleTypesGiven() throws Exception {
        Set<IRuleType> ruleTypes = Set.of(SonarRuleType.VULNERABILITY, SonarRuleType.CODE_SMELL);

        File outputFile = File.createTempFile("warnings", null);
        File temp = Files.createTempDirectory("tempDir").toFile();

        runMiner(
                REPOS_TXT,
                outputFile.getPath(),
                temp.getPath(),
                Constants.ARG_RULE_TYPES,
                ruleTypes.stream().map(IRuleType::getName).collect(Collectors.joining(",")));

        List<String> expectedChecks =
                RuleProvider.getRulesByType(ruleTypes).stream()
                        .map(Rule::getKey)
                        .sorted()
                        .collect(Collectors.toList());
        List<String> actualChecks = extractSortedCheckNames(outputFile.toPath());

        assertThat(actualChecks, equalTo(expectedChecks));
    }

    /**
     * Tests that the warnings miner can handle searching a project in which a directory name ends
     * with ".java". See https://github.com/SpoonLabs/sorald/issues/207 for context.
     */
    @Test
    public void warningsMiner_canAnalyzeFile_inDirectoryWithJavaExtension(@TempDir File workdir)
            throws Exception {
        File dirWithJavaExtension =
                workdir.toPath().resolve("project" + Constants.JAVA_EXT).toFile();
        assertTrue(dirWithJavaExtension.mkdir(), "failed to create test directory");
        Files.writeString(
                dirWithJavaExtension.toPath().resolve("Main.java"),
                "public class Main { double a = 1f / 2f; }");

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out));

        Main.main(
                new String[] {
                    Constants.MINE_COMMAND_NAME, Constants.ARG_SOURCE, workdir.toString()
                });

        assertThat(out.toString(), containsString("S2164=1"));
    }

    /** Test that extracting warnings gives results even for rules that are not violated. */
    @Test
    public void extractWarnings_accountsForAllRules_whenManyAreNotViolated() throws Exception {
        File outputFile = File.createTempFile("warnings", null),
                temp = Files.createTempDirectory("tempDir").toFile();

        runMiner(REPOS_TXT, outputFile.getPath(), temp.getPath());

        List<String> expectedChecks =
                RuleProvider.getAllRules().stream()
                        .map(Rule::getKey)
                        .sorted()
                        .collect(Collectors.toList());
        List<String> actualChecks = extractSortedCheckNames(outputFile.toPath());

        assertThat(actualChecks, equalTo(expectedChecks));
    }

    /** Test that extracting warnings gives results even for rules that are not violated. */
    @Test
    public void extractWarnings_statsOutput_containsExpectedAttributes() throws Exception {
        File outputFile = File.createTempFile("warnings", null),
                temp = Files.createTempDirectory("tempDir").toFile(),
                statsFile = File.createTempFile("stats", null);

        runMiner(
                REPOS_TXT,
                outputFile.getPath(),
                temp.getPath(),
                Constants.ARG_STATS_OUTPUT_FILE,
                statsFile.getPath());

        JSONObject jo = FileUtils.readJSON(statsFile.toPath());
        JSONObject executionInfo = jo.getJSONObject(StatsMetadataKeys.EXECUTION_INFO);

        assertThat(
                executionInfo.get(StatsMetadataKeys.SORALD_VERSION),
                equalTo(
                        SoraldVersionProvider.getVersionFromManifests(
                                SoraldVersionProvider.DEFAULT_RESOURCE_NAME)));
        assertThat(
                executionInfo.get(StatsMetadataKeys.JAVA_VERSION),
                equalTo(System.getProperty(Constants.JAVA_VERSION_SYSTEM_PROPERTY)));
        assertThat(
                executionInfo.getJSONArray(StatsMetadataKeys.ORIGINAL_ARGS).toList().size(),
                greaterThan(0));
        assertThat(jo.getLong(StatsMetadataKeys.TOTAL_MINING_TIME), greaterThan(0L));
        assertThat(jo.getJSONArray(StatsMetadataKeys.MINED_RULES).toList().size(), greaterThan(0));
        assertTrue(jo.has(StatsMetadataKeys.MINING_START_TIME));
        assertTrue(jo.has(StatsMetadataKeys.MINING_END_TIME));
    }

    @Test
    void canDetectRuleViolation_thatRequiresClasspath_whenResolvingClasspathInMavenProject(
            @TempDir File tempdir) throws Exception {
        Path statsFile = tempdir.toPath().resolve("stats.json");
        Path projectRoot =
                TestHelper.PATH_TO_RESOURCES_FOLDER
                        .resolve("scenario_test_files")
                        .resolve("classpath-dependent-project");
        Path source = projectRoot.resolve("src").resolve("main").resolve("java");

        String[] args = {
            Constants.MINE_COMMAND_NAME,
            Constants.ARG_STATS_OUTPUT_FILE,
            statsFile.toString(),
            Constants.ARG_SOURCE,
            source.toString(),
            Constants.ARG_HANDLED_RULES,
            Constants.ARG_RESOLVE_CLASSPATH_FROM,
            projectRoot.toString()
        };
        Main.main(args);

        JSONObject stats = FileUtils.readJSON(statsFile);
        JSONArray repairs = stats.getJSONArray(StatsMetadataKeys.MINED_RULES);
        assertThat(repairs.length(), equalTo(1));
        JSONObject repair = repairs.getJSONObject(0);
        assertThat(
                repair.getString(StatsMetadataKeys.REPAIR_RULE_KEY),
                equalTo(new CastArithmeticOperandProcessor().getRuleKey()));
    }

    /** We currently only support resolving the classpath on Maven projects. */
    @Test
    void exitsNonZero_whenResolvingClasspathOnNonMavenProject() {
        String[] args = {
            Constants.MINE_COMMAND_NAME,
            Constants.ARG_SOURCE,
            TestHelper.PATH_TO_RESOURCES_FOLDER.toString(),
            Constants.ARG_RESOLVE_CLASSPATH_FROM,
            TestHelper.PATH_TO_RESOURCES_FOLDER.toString()
        };

        assertThrows(SystemExitHandler.NonZeroExit.class, () -> Main.main(args));
    }

    @Nested
    class ChecksWithDeprecatedRuleKeys {

        private void doesViolationExist(String filename, String violation) {
            // arrange
            String[] args = {
                Constants.MINE_COMMAND_NAME,
                Constants.ARG_SOURCE,
                TestHelper.PATH_TO_RESOURCES_FOLDER
                        .resolve("warning_miner")
                        .resolve("deprecated_checks")
                        .resolve(filename)
                        .toString()
            };

            // act
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            System.setOut(new PrintStream(out));
            Main.main(args);

            // assert
            assertThat(out.toString(), containsString(violation));
        }

        @Test
        void report_S100_BadMethodNameCheck() {
            doesViolationExist("S100_BadMethodNameCheck.java", "S100=1");
        }

        @Test
        void report_S101_BadClassNameCheck() {
            doesViolationExist("S101_BadClassNameCheck.java", "S101=1");
        }

        @Test
        void report_S1176_UndocumentedApiCheck() {
            doesViolationExist("S1176_UndocumentedApiCheck.java", "S1176=1");
        }
    }

    @Nested
    class MineWarningsWithRuleParameters {
        @Test
        void S1176_shouldNotBeReportedIfParametersAreNotPassed() {
            String[] args = {
                Constants.MINE_COMMAND_NAME,
                Constants.ARG_SOURCE,
                TestHelper.PATH_TO_RESOURCES_FOLDER
                        .resolve("warning_miner")
                        .resolve("rule_parameters")
                        .resolve("S1176")
                        .resolve("Javadoc.java")
                        .toString(),
            };

            // act
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            System.setOut(new PrintStream(out));
            Main.main(args);

            // assert
            assertThat(out.toString(), not(containsString("S1176=1")));
        }

        @Test
        void S1176_shouldBeReported() {
            String[] args = {
                Constants.MINE_COMMAND_NAME,
                Constants.ARG_SOURCE,
                TestHelper.PATH_TO_RESOURCES_FOLDER
                        .resolve("warning_miner")
                        .resolve("rule_parameters")
                        .resolve("S1176")
                        .resolve("Javadoc.java")
                        .toString(),
                Constants.ARG_RULE_PARAMETERS,
                TestHelper.PATH_TO_RESOURCES_FOLDER
                        .resolve("warning_miner")
                        .resolve("rule_parameters")
                        .resolve("S1176")
                        .resolve("params.json")
                        .toString(),
            };

            // act
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            System.setOut(new PrintStream(out));
            Main.main(args);

            // assert
            assertThat(out.toString(), containsString("S1176=1"));
        }
    }

    @Nested
    class MineWarningsWithRuleKeys {
        @Test
        void onlyMineRulesWhichAreAskedFor_S1068_S109() {
            // arrange
            String[] args = {
                Constants.MINE_COMMAND_NAME,
                Constants.ARG_SOURCE,
                TestHelper.PATH_TO_RESOURCES_FOLDER
                        .resolve("warning_miner")
                        .resolve("specified_rules")
                        .toString(),
                Constants.ARG_RULE_KEYS,
                "S1068,S109"
            };

            // act
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            System.setOut(new PrintStream(out));
            Main.main(args);

            // assert
            assertPresenceOfS1068AndS109(out);
        }

        @Test
        void ruleKeysParameterShouldOverrideOtherFilters_handledRules_ruleTypes() {
            // arrange
            String[] args = {
                Constants.MINE_COMMAND_NAME,
                Constants.ARG_SOURCE,
                TestHelper.PATH_TO_RESOURCES_FOLDER
                        .resolve("warning_miner")
                        .resolve("specified_rules")
                        .toString(),
                Constants.ARG_HANDLED_RULES,
                Constants.ARG_RULE_TYPES,
                RuleType.CODE_SMELL.name(),
                Constants.ARG_RULE_KEYS,
                "S1068,S109"
            };

            // act
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            System.setOut(new PrintStream(out));
            Main.main(args);

            // assert
            assertPresenceOfS1068AndS109(out);
        }

        private void assertPresenceOfS1068AndS109(ByteArrayOutputStream output) {
            assertThat(output.toString(), containsString("S1068=1"));
            assertThat(output.toString(), containsString("S109=1"));
            assertThat(output.toString(), not(containsString("S1106=1")));
        }
    }

    private static void runMiner(
            Path pathToRepos, String pathToOutput, String pathToTempDir, String... extraArgs)
            throws Exception {
        String[] baseArgs =
                new String[] {
                    Constants.MINE_COMMAND_NAME,
                    Constants.ARG_STATS_ON_GIT_REPOS,
                    Constants.ARG_GIT_REPOS_LIST,
                    pathToRepos.toString(),
                    Constants.ARG_MINER_OUTPUT_FILE,
                    pathToOutput,
                    Constants.ARG_TEMP_DIR,
                    pathToTempDir
                };
        String[] fullArgs =
                Stream.of(baseArgs, extraArgs).flatMap(Arrays::stream).toArray(String[]::new);
        Main.main(fullArgs);
    }

    /** Extract check names from the warnings miner output file, sorted lexicographically. */
    private static List<String> extractSortedCheckNames(Path outputFile) throws IOException {
        Pattern checkNamePattern = Pattern.compile("^(.*)=\\d+$");
        return Files.readAllLines(outputFile).stream()
                .map(checkNamePattern::matcher)
                .filter(Matcher::matches)
                .map(m -> m.group(1))
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Extract all lines from a warning miner results file for which the check has >0 detections,
     * sorted lexicographically. Expect each relevant line to be on the form "<rule-key></>=%d",
     * where %d is any non-negative integer.
     */
    private static List<String> extractSortedNonZeroChecks(Path minerResults) throws IOException {
        return Files.readAllLines(minerResults).stream()
                .filter(s -> s.matches("^.*=\\d+$"))
                .filter(s -> !s.matches("^.*=0\\s*$"))
                .sorted()
                .collect(Collectors.toList());
    }
}
