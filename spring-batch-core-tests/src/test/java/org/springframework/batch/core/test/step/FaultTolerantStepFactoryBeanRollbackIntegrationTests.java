package org.springframework.batch.core.test.step;

import static org.junit.Assert.assertEquals;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sql.DataSource;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.item.FaultTolerantStepFactoryBean;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.ParseException;
import org.springframework.batch.item.UnexpectedInputException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.ParameterizedRowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.jdbc.SimpleJdbcTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.Assert;

/**
 * Tests for {@link FaultTolerantStepFactoryBean}.
 */
@ContextConfiguration(locations = "/simple-job-launcher-context.xml")
@RunWith(SpringJUnit4ClassRunner.class)
public class FaultTolerantStepFactoryBeanRollbackIntegrationTests {

	private static final int MAX_COUNT = 1000;

	private final Log logger = LogFactory.getLog(getClass());

	private FaultTolerantStepFactoryBean<String, String> factory;

	private SkipReaderStub reader;

	private SkipProcessorStub processor;

	private SkipWriterStub writer;

	private JobExecution jobExecution;

	private StepExecution stepExecution;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JobRepository repository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Before
	public void setUp() throws Exception {

		reader = new SkipReaderStub();
		writer = new SkipWriterStub(dataSource);
		processor = new SkipProcessorStub(dataSource);

		factory = new FaultTolerantStepFactoryBean<String, String>();

		factory.setBeanName("stepName");
		factory.setTransactionManager(transactionManager);
		factory.setJobRepository(repository);
		factory.setCommitInterval(3);
		factory.setSkipLimit(10);

		SimpleJdbcTestUtils.deleteFromTables(new SimpleJdbcTemplate(dataSource), "ERROR_LOG");

	}

	@Test
	public void testUpdatesNoRollback() throws Exception {

		SimpleJdbcTemplate jdbcTemplate = new SimpleJdbcTemplate(dataSource);

		writer.write(Arrays.asList("foo", "bar"));
		processor.process("spam");
		assertEquals(3, SimpleJdbcTestUtils.countRowsInTable(jdbcTemplate, "ERROR_LOG"));

		writer.clear();
		processor.clear();
		assertEquals(0, SimpleJdbcTestUtils.countRowsInTable(jdbcTemplate, "ERROR_LOG"));

	}

	@Test
	public void testMultithreadedSkipInWriter() throws Throwable {

		ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
		taskExecutor.setCorePoolSize(3);
		taskExecutor.setMaxPoolSize(6);
		taskExecutor.setQueueCapacity(0);
		taskExecutor.afterPropertiesSet();
		factory.setTaskExecutor(taskExecutor);

		@SuppressWarnings("unchecked")
		Map<Class<? extends Throwable>, Boolean> skippable = getExceptionMap(Exception.class);
		factory.setSkippableExceptionClasses(skippable);

		jobExecution = repository.createJobExecution("skipJob", new JobParameters());

		for (int i = 0; i < MAX_COUNT; i++) {

			if (i % 100 == 0) {
				logger.info("Starting step: " + i);
			}

			SimpleJdbcTemplate jdbcTemplate = new SimpleJdbcTemplate(dataSource);
			assertEquals(0, SimpleJdbcTestUtils.countRowsInTable(jdbcTemplate, "ERROR_LOG"));

			try {

				reader.clear();
				reader.setItems("1", "2", "3", "4", "5");
				factory.setItemReader(reader);
				writer.clear();
				factory.setItemWriter(writer);
				processor.clear();
				factory.setItemProcessor(processor);

				writer.setFailures("1", "2", "3", "4", "5");

				Step step = (Step) factory.getObject();

				stepExecution = jobExecution.createStepExecution(factory.getName());
				repository.add(stepExecution);
				step.execute(stepExecution);
				assertEquals(BatchStatus.COMPLETED, stepExecution.getStatus());

				assertEquals("[]", writer.getCommitted().toString());
				assertEquals("[]", processor.getCommitted().toString());
				List<String> processed = new ArrayList<String>(processor.getProcessed());
				Collections.sort(processed);
				assertEquals("[1, 1, 2, 2, 3, 3, 4, 4, 5, 5]", processed.toString());
				assertEquals(5, stepExecution.getSkipCount());

			}
			catch (Throwable e) {
				logger.info("Failed on iteration " + i + " of " + MAX_COUNT);
				throw e;
			}

		}

	}

	private Map<Class<? extends Throwable>, Boolean> getExceptionMap(Class<? extends Throwable>... args) {
		Map<Class<? extends Throwable>, Boolean> map = new HashMap<Class<? extends Throwable>, Boolean>();
		for (Class<? extends Throwable> arg : args) {
			map.put(arg, true);
		}
		return map;
	}

	private static class SkipReaderStub implements ItemReader<String> {

		private String[] items;

		private int counter = -1;

		public SkipReaderStub() throws Exception {
			super();
		}

		public void setItems(String... items) {
			Assert.isTrue(counter < 0, "Items cannot be set once reading has started");
			this.items = items;
		}

		public void clear() {
			counter = -1;
		}

		public synchronized String read() throws Exception, UnexpectedInputException, ParseException {
			counter++;
			if (counter >= items.length) {
				return null;
			}
			String item = items[counter];
			return item;
		}
	}

	private static class SkipWriterStub implements ItemWriter<String> {

		private List<String> written = new CopyOnWriteArrayList<String>();

		private Collection<String> failures = Collections.emptySet();

		private SimpleJdbcTemplate jdbcTemplate;

		public SkipWriterStub(DataSource dataSource) {
			jdbcTemplate = new SimpleJdbcTemplate(dataSource);
		}

		public void setFailures(String... failures) {
			this.failures = Arrays.asList(failures);
		}

		public List<String> getCommitted() {
			return jdbcTemplate.query("SELECT MESSAGE from ERROR_LOG where STEP_NAME='written'",
					new ParameterizedRowMapper<String>() {
						public String mapRow(ResultSet rs, int rowNum) throws SQLException {
							return rs.getString(1);
						}
					});
		}

		public void clear() {
			written.clear();
			jdbcTemplate.update("DELETE FROM ERROR_LOG where STEP_NAME='written'");
		}

		public void write(List<? extends String> items) throws Exception {
			for (String item : items) {
				written.add(item);
				jdbcTemplate.update("INSERT INTO ERROR_LOG (MESSAGE, STEP_NAME) VALUES (?, ?)", item, "written");
				checkFailure(item);
			}
		}

		private void checkFailure(String item) {
			if (failures.contains(item)) {
				throw new RuntimeException("Planned failure");
			}
		}
	}

	private static class SkipProcessorStub implements ItemProcessor<String, String> {

		private final Log logger = LogFactory.getLog(getClass());

		private List<String> processed = new CopyOnWriteArrayList<String>();

		private SimpleJdbcTemplate jdbcTemplate;

		/**
		 * @param dataSource
		 */
		public SkipProcessorStub(DataSource dataSource) {
			jdbcTemplate = new SimpleJdbcTemplate(dataSource);
		}

		/**
		 * @return the processed
		 */
		public List<String> getProcessed() {
			return processed;
		}

		public List<String> getCommitted() {
			return jdbcTemplate.query("SELECT MESSAGE from ERROR_LOG where STEP_NAME='processed'",
					new ParameterizedRowMapper<String>() {
						public String mapRow(ResultSet rs, int rowNum) throws SQLException {
							return rs.getString(1);
						}
					});
		}

		public void clear() {
			processed.clear();
			jdbcTemplate.update("DELETE FROM ERROR_LOG where STEP_NAME='processed'");
		}

		public String process(String item) throws Exception {
			processed.add(item);
			logger.debug("Processed item: " + item);
			jdbcTemplate.update("INSERT INTO ERROR_LOG (MESSAGE, STEP_NAME) VALUES (?, ?)", item, "processed");
			return item;
		}
	}

}
