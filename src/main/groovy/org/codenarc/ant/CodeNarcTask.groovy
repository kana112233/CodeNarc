/*
 * Copyright 2008 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.codenarc.ant

import static java.lang.Thread.currentThread

import org.codenarc.ruleset.RuleSet
import org.codenarc.util.io.ResourceFactory
import org.slf4j.Logger

import org.apache.tools.ant.BuildException
import org.apache.tools.ant.Task
import org.apache.tools.ant.types.FileSet
import org.apache.tools.ant.types.Path
import org.apache.tools.ant.types.Reference
import org.codenarc.CodeNarcRunner
import org.codenarc.analyzer.SourceAnalyzer
import org.codenarc.report.ReportWriterFactory
import org.codenarc.results.Results
import org.codenarc.util.BaselineResultsProcessor
import org.codenarc.util.io.DefaultResourceFactory
import org.slf4j.LoggerFactory

/**
 * Ant Task for running CodeNarc.
 * <p/>
 * The <code>ruleSetFiles</code> property specifies the path to the Groovy or XML RuleSet
 * definition files, relative to the classpath. This can be a single file path, or multiple
 * paths separated by commas. It is required.
 * <p/>
 * The <code>maxPriority1Violations</code> property specifies the maximum number of priority 1
 * violations allowed before failing the build (throwing a BuildException). Likewise,
 * <code>maxPriority2Violations</code> and <code>maxPriority3Violations</code> specify the
 * thresholds for violations of priority 2 and 3.
 * <p/>
 * At least one nested <code>fileset</code> element is required, and is used to specify the source files
 * to be analyzed. This is the standard Ant <i>FileSet</i>, and is quite powerful and flexible.
 * See the <i>Apache Ant Manual</i> for more information on <i>FileSets</i>.
 * <p/>
 * The <ode>report</code> nested element defines the format and output file for the analysis report.
 * Currently, HTML (type="html") and XML (type="xml") are the only supported formats. Each report
 * is configured using nested <code>option</code> elements, with <code>name</code>, and
 * <code>value</code> attributes.
 *
 * @see "http://ant.apache.org/manual/index.html"
 *
 * @author Chris Mair
 */
class CodeNarcTask extends Task {

    private static final Logger LOG = LoggerFactory.getLogger(CodeNarcTask)

    /**
     * The path to the Groovy or XML RuleSet definition files, relative to the classpath. This can be a
     * single file path, or multiple paths separated by commas.
     */
    String ruleSetFiles

    /**
     * The path to a Baseline Violations report (report type "baseline"). If set, then all violations specified
     * within that report are excluded (filtered) from the current CodeNarc run. If null/empty, then do nothing.
     */
    String excludeBaseline

    int maxPriority1Violations = Integer.MAX_VALUE
    int maxPriority2Violations = Integer.MAX_VALUE
    int maxPriority3Violations = Integer.MAX_VALUE

    /**
     * Classpath used when compiling analysed classes.
     */
    Path classpath

    protected List reportWriters = []
    protected List fileSets = []
    protected RuleSet ruleSet

    private final ResourceFactory resourceFactory = new DefaultResourceFactory()

    // Abstract creation of the CodeNarcRunner instance to allow substitution of test spy for unit tests
    protected Closure createCodeNarcRunner = {
        if (excludeBaseline) {
            LOG.info("Loading baseline violations from [$excludeBaseline]")
            def resource = resourceFactory.getResource(excludeBaseline)
            def resultsProcessor = new BaselineResultsProcessor(resource)
            return new CodeNarcRunner(resultsProcessor:resultsProcessor)
        }
        return new CodeNarcRunner()
    }

    /**
     * Execute this Ant Task
     */
    @Override
    void execute() throws BuildException {
        assert ruleSetFiles
        assert fileSets

        def sourceAnalyzer = createSourceAnalyzer()
        def codeNarcRunner = createCodeNarcRunner()
        codeNarcRunner.ruleSetFiles = ruleSetFiles
        codeNarcRunner.reportWriters = reportWriters
        codeNarcRunner.sourceAnalyzer = sourceAnalyzer

        def results = executeRunnerWithConfiguredClasspath(codeNarcRunner)

        checkMaxViolations(results)
    }

    void addFileset(FileSet fileSet) {
        assert fileSet
        this.fileSets << fileSet
    }

    /**
     * Ant-defined method (by convention), called with each instance of a nested <report>
     * element within this task.
     */
    void addConfiguredReport(Report report) {
        reportWriters << new ReportWriterFactory().getReportWriter(report.type, report.options)
    }

    Path createClasspath() {
        classpath = classpath ?: new Path(getProject())
        classpath.createPath()
    }

    /**
     * Adds a reference to a classpath defined elsewhere to be used when compiling analysed classes.
     */
    void setClasspathRef(Reference reference) {
        createClasspath().refid = reference
    }

    /**
     * Create and return the SourceAnalyzer
     * @return a configured SourceAnalyzer instance
     */
    protected SourceAnalyzer createSourceAnalyzer() {
        new AntFileSetSourceAnalyzer(getProject(), fileSets)
    }

    private void checkMaxViolations(Results results) {
        def p1 = results.getNumberOfViolationsWithPriority(1, true)
        def p2 = results.getNumberOfViolationsWithPriority(2, true)
        def p3 = results.getNumberOfViolationsWithPriority(3, true)
        def countsText = "(p1=$p1; p2=$p2; p3=$p3)"

        checkMaxViolationForPriority(1, p1, countsText)
        checkMaxViolationForPriority(2, p2, countsText)
        checkMaxViolationForPriority(3, p3, countsText)
    }

    private void checkMaxViolationForPriority(int priority, int count, String countsText) {
        if (count > this."maxPriority${priority}Violations") {
            throw new BuildException("Exceeded maximum number of priority ${priority} violations: " + countsText)
        }
    }

    @SuppressWarnings('MethodParameterTypeRequired')
    private Results executeRunnerWithConfiguredClasspath(codeNarcRunner) {
        def paths = classpath?.list()
        if (paths) {
            def oldContextClassLoader = currentThread().contextClassLoader
            try {
                currentThread().contextClassLoader = classLoaderForPaths(paths, oldContextClassLoader)
                codeNarcRunner.execute()
            } finally {
                currentThread().contextClassLoader = oldContextClassLoader
            }
        } else {
            codeNarcRunner.execute()
        }
    }

    private ClassLoader classLoaderForPaths(String[] paths, ClassLoader parent) {
        def urls = paths.collect { new File(it).toURI().toURL() } as URL[]
        new URLClassLoader(urls, parent)
    }
}
