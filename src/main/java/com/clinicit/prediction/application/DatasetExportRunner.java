package com.clinicit.prediction.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes the wait-time training dataset and exits:
 * {@code java -jar clinicit.jar --spring.main.web-application-type=none
 * --clinicit.prediction.dataset-export-path=wait_times.csv}. Read-only on the database.
 */
@Component
@ConditionalOnProperty("clinicit.prediction.dataset-export-path")
class DatasetExportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatasetExportRunner.class);

    private final WaitTimeDatasetBuilder dataset;
    private final PredictionProperties properties;
    private final ApplicationContext context;

    DatasetExportRunner(WaitTimeDatasetBuilder dataset, PredictionProperties properties, ApplicationContext context) {
        this.dataset = dataset;
        this.properties = properties;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Path path = Path.of(properties.datasetExportPath());
        var examples = dataset.build(null);
        try (Writer out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            dataset.writeCsv(examples, out);
        }
        log.info("Wrote {} wait-time examples to {}", examples.size(), path);
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
