/*
 * Copyright (c) 2015 - Present. The STARTS Team. All Rights Reserved.
 */

package edu.illinois.starts.jdeps;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.logging.Level;

import edu.illinois.starts.constants.StartsConstants;
import edu.illinois.starts.enums.DependencyFormat;
import edu.illinois.starts.helpers.Cache;
import edu.illinois.starts.helpers.Loadables;
import edu.illinois.starts.helpers.PomUtil;
import edu.illinois.starts.helpers.RTSUtil;
import edu.illinois.starts.helpers.Writer;
import edu.illinois.starts.util.Logger;
import edu.illinois.yasgl.DirectedGraph;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.surefire.AbstractSurefireMojo;
import org.apache.maven.plugin.surefire.SurefirePlugin;
import org.apache.maven.plugin.surefire.util.DirectoryScanner;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.surefire.booter.Classpath;
import org.apache.maven.surefire.booter.SurefireExecutionException;
import org.apache.maven.surefire.testset.TestListResolver;
import org.apache.maven.surefire.util.DefaultScanResult;

/**
 * Base MOJO for the JDeps-Based STARTS.
 */
abstract class BaseMojo extends SurefirePlugin implements StartsConstants {
    /**
     * Set this to "false" to not filter out "sun.*" and "java.*" classes from jdeps parsing.
     */
    @Parameter(property = "filterLib", defaultValue = "false")
    protected boolean filterLib;

    /**
     * Set this to "true" to enable test time estimation.
     */
    @Parameter(property = "estimateSelect", defaultValue = "false")
    protected boolean estimateSelect;

    /**
     * The directory in which to store STARTS artifacts that are needed between runs.
     */
    protected String artifactsDir;

    /**
     * Allows to switch the format in which we want to store the test dependencies.
     * A full list of what we currently support can be found in
     * @see edu.illinois.starts.enums.DependencyFormat
     */
    @Parameter(property = "depFormat", defaultValue = "ZLC")
    protected DependencyFormat depFormat;

    /**
     * Path to directory that contains the result of running jdeps on third-party
     * and standard library jars that an application may need, e.g., those in M2_REPO.
     */
    @Parameter(property = "gCache", defaultValue = "${basedir}${file.separator}jdeps-cache")
    protected String graphCache;

    /**
     * Set this to "false" to not print the graph obtained from jdeps parsing.
     * When "true" the graph is written to file after the run.
     */
    @Parameter(property = "printGraph", defaultValue = "true")
    protected boolean printGraph;

    /**
     * Output filename for the graph, if printGraph == true.
     */
    @Parameter(defaultValue = "graph", readonly = true, required = true)
    protected String graphFile;

    /**
     * Log levels as defined in java.util.logging.Level.
     */
    @Parameter(property = "startsLogging", defaultValue = "CONFIG")
    protected String loggingLevel;

    private Classpath sureFireClassPath;

    protected void printResult(Set<String> set, String title) {
        Writer.writeToLog(set, title, Logger.getGlobal());
    }

    public String getArtifactsDir() throws MojoExecutionException {
        if (artifactsDir == null) {
            artifactsDir = basedir.getAbsolutePath() + File.separator + STARTS_DIRECTORY_PATH;
            File file = new File(artifactsDir);
            if (!file.mkdirs() && !file.exists()) {
                throw new MojoExecutionException("I could not create artifacts dir: " + artifactsDir);
            }
        }
        return artifactsDir;
    }

    public void setIncludesExcludes() throws MojoExecutionException {
        long start = System.currentTimeMillis();
        try {
            Field projectField = AbstractSurefireMojo.class.getDeclaredField("project");
            projectField.setAccessible(true);
            MavenProject accessedProject = (MavenProject) projectField.get(this);
            List<String> includes = PomUtil.getFromPom("include", accessedProject);
            List<String> excludes = PomUtil.getFromPom("exclude", accessedProject);
            Logger.getGlobal().log(Level.FINEST, "@@Excludes: " + excludes);
            Logger.getGlobal().log(Level.FINEST,"@@Includes: " + includes);
            setIncludes(includes);
            setExcludes(excludes);
        } catch (NoSuchFieldException nsfe) {
            nsfe.printStackTrace();
        } catch (IllegalAccessException iae) {
            iae.printStackTrace();
        }
        long end = System.currentTimeMillis();
        Logger.getGlobal().log(Level.FINE, "[PROFILE] updateForNextRun(setIncludesExcludes): "
                + Writer.millsToSeconds(end - start));
    }

    public List getTestClasses(String methodName) {
        long start = System.currentTimeMillis();
        DefaultScanResult defaultScanResult = null;
        try {
            Method scanMethod = AbstractSurefireMojo.class.getDeclaredMethod("scanForTestClasses", null);
            scanMethod.setAccessible(true);
            defaultScanResult = (DefaultScanResult) scanMethod.invoke(this, null);
        } catch (NoSuchMethodException nsme) {
            nsme.printStackTrace();
        } catch (InvocationTargetException ite) {
            ite.printStackTrace();
        } catch (IllegalAccessException iae) {
            iae.printStackTrace();
        }
        long end = System.currentTimeMillis();
        Logger.getGlobal().log(Level.FINE, "[PROFILE] " + methodName + "(getTestClasses): "
                + Writer.millsToSeconds(end - start));
        return (List<String>) defaultScanResult.getFiles();
    }

    public ClassLoader createClassLoader(Classpath sfClassPath) {
        long start = System.currentTimeMillis();
        ClassLoader loader = null;
        try {
            loader = sfClassPath.createClassLoader(null, false, false, "MyRole");
        } catch (SurefireExecutionException see) {
            see.printStackTrace();
        }
        long end = System.currentTimeMillis();
        Logger.getGlobal().log(Level.FINE, "[PROFILE] updateForNextRun(createClassLoader): "
                + Writer.millsToSeconds(end - start));
        return loader;
    }

    protected class Result {
        private Map<String, Set<String>> testDeps;
        private DirectedGraph<String> graph;
        private Set<String> affectedTests;
        private Set<String> unreachedDeps;

        public Result(Map<String, Set<String>> testDeps, DirectedGraph<String> graph,
                      Set<String> affectedTests, Set<String> unreached) {
            this.testDeps = testDeps;
            this.graph = graph;
            this.affectedTests = affectedTests;
            this.unreachedDeps = unreached;
        }

        public Map<String, Set<String>> getTestDeps() {
            return testDeps;
        }

        public DirectedGraph<String> getGraph() {
            return graph;
        }

        public Set<String> getAffectedTests() {
            return affectedTests;
        }

        public Set<String> getUnreachedDeps() {
            return unreachedDeps;
        }
    }

    public Classpath getSureFireClassPath() throws MojoExecutionException {
        long start = System.currentTimeMillis();
        if (sureFireClassPath == null) {
            try {
                sureFireClassPath = new Classpath(getProject().getTestClasspathElements());
            } catch (DependencyResolutionRequiredException drre) {
                drre.printStackTrace();
            }
        }
        Logger.getGlobal().log(Level.FINEST, "SF-CLASSPATH: " + sureFireClassPath.getClassPath());
        long end = System.currentTimeMillis();
        Logger.getGlobal().log(Level.FINE, "[PROFILE] updateForNextRun(getSureFireClassPath): "
                + Writer.millsToSeconds(end - start));
        return sureFireClassPath;
    }

    public Result prepareForNextRun(String sfPathString, Classpath sfClassPath, List<String> classesToAnalyze,
                                    Set<String> nonAffected, boolean computeUnreached) throws MojoExecutionException {
        long start = System.currentTimeMillis();
        String m2Repo = getLocalRepository().getBasedir();
        File jdepsCache = new File(graphCache);
        // We store the jdk-graphs at the root of "jdepsCache" directory, with
        // jdk.graph being the file that merges all the graphs for all standard
        // library jars.
        File libraryFile = new File(jdepsCache, "jdk.graph");
        // Create the Loadables object early so we can use its helpers
        Loadables loadables = new Loadables(classesToAnalyze, artifactsDir, sfPathString, filterLib, jdepsCache);
        // Surefire Classpath object is easier to iterate over without de-constructing
        // sfPathString (which we use in a number of other places)
        loadables.setSurefireClasspath(sfClassPath);

        List<String> moreEdges = new ArrayList<String>();
        long loadMoreEdges = System.currentTimeMillis();
        Cache cache = new Cache(jdepsCache, m2Repo);
        // 1. Load non-reflection edges from third-party libraries in the classpath
        cache.loadM2EdgesFromCache(moreEdges, sfPathString);
        long loadM2EdgesFromCache = System.currentTimeMillis();
        // 2. Get non-reflection edges from CUT and SDK; use (1) to build graph
        loadables.create(new ArrayList<>(moreEdges), sfClassPath, computeUnreached);

        Map<String, Set<String>> transitiveClosure = loadables.getTransitiveClosure();
        long createLoadables = System.currentTimeMillis();

        // We don't need to compute affected tests this way with ZLC format.
        // In RTSUtil.computeAffectedTests(), we find affected tests by (a) removing nonAffected tests from the set of
        // all tests and then (b) adding all tests that reach to * as affected if there has been a change. This is only
        // for CLZ which does not encode information about *. ZLC already encodes and reasons about * when it finds
        // nonAffected tests.
        Set<String> affected = depFormat == DependencyFormat.ZLC ? null
                : RTSUtil.computeAffectedTests(new HashSet<>(classesToAnalyze),
                nonAffected, transitiveClosure);
        long end = System.currentTimeMillis();
        Logger.getGlobal().log(Level.FINE, "[PROFILE] prepareForNextRun(loadMoreEdges): "
                + Writer.millsToSeconds(loadMoreEdges - start));
        Logger.getGlobal().log(Level.FINE, "[PROFILE] prepareForNextRun(loadM2EdgesFromCache): "
                + Writer.millsToSeconds(loadM2EdgesFromCache - loadMoreEdges));
        Logger.getGlobal().log(Level.FINE, "[PROFILE] prepareForNextRun(createLoadable): "
                + Writer.millsToSeconds(createLoadables - loadM2EdgesFromCache));
        Logger.getGlobal().log(Level.FINE, "[PROFILE] prepareForNextRun(computeAffectedTests): "
                + Writer.millsToSeconds(end - createLoadables));
        Logger.getGlobal().log(Level.FINE, "[PROFILE] updateForNextRun(prepareForNextRun(TOTAL)): "
                + Writer.millsToSeconds(end - start));
        return new Result(transitiveClosure, loadables.getGraph(), affected, loadables.getUnreached());
    }

    protected List<String> getAllClasses() {
        DirectoryScanner testScanner = new DirectoryScanner(getTestClassesDirectory(), new TestListResolver("*"));
        DirectoryScanner classScanner = new DirectoryScanner(getClassesDirectory(), new TestListResolver("*"));
        DefaultScanResult scanResult = classScanner.scan().append(testScanner.scan());
        return scanResult.getFiles();
    }

    public Map<String, String> getTestTimesFromSurefileReports() {
        Map<String, String> curTestTimes = new HashMap<>();
        final String time = "Time elapsed:";
        try {
            FilenameFilter textFilter = new FilenameFilter() {
                public boolean accept(File dir, String name) {
                    return name.endsWith(".txt");
                }
            };
            File dir = getReportsDirectory();
            if (!dir.exists()) {
                return curTestTimes;
            }
            File[] files = dir.listFiles(textFilter);
            for (File file : files) {
                Scanner scanner = new Scanner(file);
                int nameLength = time.length();
                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine();
                    int timeStart = line.indexOf(time);
                    if (timeStart > 0) {
                        timeStart += nameLength + 1;
                        int timeEnd = line.indexOf("s", timeStart) - 1;
                        curTestTimes.put(line.substring(timeEnd + 8), line.substring(timeStart, timeEnd));
                    }
                }
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return curTestTimes;
    }

    public Set<String> getEstimatedTestTimes(Set<String> affectedTests) {
        Set<String> result = new HashSet<>();
        try {
            File testTimeLog = new File(STARTS_DIRECTORY_PATH, STARTS_SELECT_TIME_TABLE);
            if (!testTimeLog.exists()) {
                return null;
            }
            Scanner scanner = new Scanner(testTimeLog);
            double sum = 0.0;
            while (scanner.hasNextLine()) {
                String[] line = scanner.nextLine().split(" ");
                if (affectedTests.contains(line[0])) {
                    sum += Double.parseDouble(line[1]);
                    result.add(line[0] + " " + line[1] + "s//" + line[3] + "s");
                }
            }
            sum = Math.round(sum * 1000.0) / 1000.0;
            result.add("Total Estimate Time: " + sum);
            scanner.close();
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return result;
    }

    private Set<String> getNonAffectedTestsFromFile() {
        Set<String> nonAffectedTests = new HashSet<>();
        try {
            File nonAffectedTestsFile = new File(STARTS_DIRECTORY_PATH + STARTS_NON_AFFECTED_TESTS);
            Scanner scanner = new Scanner(nonAffectedTestsFile);
            while (scanner.hasNextLine()) {
                nonAffectedTests.add(scanner.nextLine());
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return nonAffectedTests;
    }

    public void updateTestTimeTable() throws MojoExecutionException {
        // Design of the Time Table
        //  0        1        2       3                  4         5         6
        // TestName AvgTime #ofTest StandardDeviaction SquareSum PastTests PastEstimate
        String timeTableName = STARTS_DIRECTORY_PATH + STARTS_SELECT_TIME_TABLE;
        Set<String> nonAffectedTests = getNonAffectedTestsFromFile();
        double roundOff = 1000.0;
        try {
            File file = new File(timeTableName);
            Map<String, String> curTestTimes = getTestTimesFromSurefileReports();
            if (!file.exists()) {
                file.createNewFile();
                Writer.writeTimeTableToFile(curTestTimes, timeTableName);
            } else {
                Scanner scanner = new Scanner(file);
                List<String> lines = new ArrayList<>();
                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine();
                    String testName = line.substring(0, line.indexOf(" "));
                    if (nonAffectedTests == null || !nonAffectedTests.contains(testName)) {
                        String[] split = line.split(" ");
                        int count = Integer.parseInt(split[2]);
                        double ave = Double.parseDouble(split[1]);
                        double cur = Double.parseDouble(curTestTimes.get(split[0]));
                        double squareSum = Double.parseDouble(split[4]) + cur * cur;
                        ave = (cur + ave * count++) / count;
                        double stdev = Math.sqrt(count * squareSum - Math.pow(ave * count, 2)) / count;
                        ave = Math.round(ave * roundOff) / roundOff;
                        stdev = Math.round(stdev * roundOff) / roundOff;
                        split[1] = " " + Double.toString(ave);
                        split[2] = " " + Integer.toString(count);
                        split[3] = " " + Double.toString(stdev);
                        split[4] = " " + Double.toString(squareSum);
                        split[5] = " " + cur + "," + split[5];
                        line = split[0] + split[1] + split[2] + split[3] + split[4] + split[5];
                        Logger.getGlobal().log(Level.INFO, "********* " + line);
                    }
                    lines.add(line);
                }
                scanner.close();
                Writer.writeToFile(lines, timeTableName);
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }
}