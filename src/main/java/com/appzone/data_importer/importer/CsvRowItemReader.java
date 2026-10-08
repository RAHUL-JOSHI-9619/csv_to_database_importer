package com.appzone.data_importer.importer;

import java.io.IOException;
import java.nio.file.Path;

import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.batch.infrastructure.item.ItemStreamReader;

import com.appzone.data_importer.common.CsvRecordReader;

/**
 * Spring Batch reader over the data CSV, using the same CsvRecordReader as the pre-check, so the
 * rows imported are exactly the rows that were checked. Skips the header row.
 */
public class CsvRowItemReader implements ItemStreamReader<String[]> {

    private final Path file;
    private CsvRecordReader reader;

    public CsvRowItemReader(Path file) {
        this.file = file;
    }

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        try {
            reader = CsvRecordReader.open(file);
            reader.next();   // header
        } catch (IOException e) {
            throw new ItemStreamException("Could not open " + file, e);
        }
    }

    @Override
    public String[] read() throws Exception {
        return reader.next();
    }

    @Override
    public void close() throws ItemStreamException {
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException ignore) {
                // nothing useful to do
            }
        }
    }
}
