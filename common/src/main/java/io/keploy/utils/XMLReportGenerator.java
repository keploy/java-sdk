package io.keploy.utils;


import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.tools.ExecFileLoader;
import org.jacoco.report.DirectorySourceFileLocator;
import org.jacoco.report.IReportVisitor;
import org.jacoco.report.xml.XMLFormatter;

public class XMLReportGenerator {

    private final String title;

    private final File executionDataFile;
    private final File classesDirectory;
    private final File sourceDirectory;
    private final File reportFile;

    private ExecFileLoader execFileLoader;


    public XMLReportGenerator(final File projectDirectory) {
        this.title = projectDirectory.getName();
        this.executionDataFile = new File(projectDirectory, "jacoco.exec");
        this.classesDirectory = new File(projectDirectory, "bin");
        this.sourceDirectory = new File(projectDirectory, "src");
        this.reportFile = new File(projectDirectory, "coverage.xml");
    }



    public void create() throws IOException {
        // Load execution data from jacoco.exec
        loadExecutionData();

        // Analyze structure and calculate coverage
        final IBundleCoverage bundleCoverage = analyzeStructure();

        // Generate XML report
        createReport(bundleCoverage);
    }


    private void createReport(final IBundleCoverage bundleCoverage) throws IOException {
        // Initialize the XML report formatter
        final XMLFormatter xmlFormatter = new XMLFormatter();
        try (FileOutputStream outputStream = new FileOutputStream(reportFile)) {
            final IReportVisitor visitor = xmlFormatter.createVisitor(outputStream);

            // Provide execution and session information
            visitor.visitInfo(
                    execFileLoader.getSessionInfoStore().getInfos(),
                    execFileLoader.getExecutionDataStore().getContents()
            );

            // Populate the report structure with coverage data
            visitor.visitBundle(
                    bundleCoverage,
                    new DirectorySourceFileLocator(sourceDirectory, "utf-8", 4)
            );

            // Finalize the report
            visitor.visitEnd();
        }

        System.out.println("JaCoCo XML coverage report generated at: " + reportFile.getAbsolutePath());
    }

    private void loadExecutionData() throws IOException {
        execFileLoader = new ExecFileLoader();
        execFileLoader.load(executionDataFile);
    }

    private IBundleCoverage analyzeStructure() throws IOException {
        final CoverageBuilder coverageBuilder = new CoverageBuilder();
        final Analyzer analyzer = new Analyzer(
                execFileLoader.getExecutionDataStore(), coverageBuilder);

        analyzer.analyzeAll(classesDirectory);

        return coverageBuilder.getBundle(title);
    }


    public static void main(final String[] args) throws IOException {
        for (String arg : args) {
            final XMLReportGenerator generator = new XMLReportGenerator(
                    new File(arg));
            generator.create();
        }
    }
}