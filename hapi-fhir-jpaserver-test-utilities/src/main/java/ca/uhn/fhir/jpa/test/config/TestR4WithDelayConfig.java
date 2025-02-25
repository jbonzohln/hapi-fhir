/*-
 * #%L
 * HAPI FHIR JPA Server Test Utilities
 * %%
 * Copyright (C) 2014 - 2025 Smile CDR, Inc.
 * %%
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
 * #L%
 */
package ca.uhn.fhir.jpa.test.config;

import ca.uhn.fhir.batch2.jobs.config.Batch2JobsConfig;
import ca.uhn.fhir.jpa.batch2.JpaBatch2Config;
import ca.uhn.fhir.jpa.config.HapiJpaConfig;
import ca.uhn.fhir.jpa.config.r4.JpaR4Config;
import ca.uhn.fhir.jpa.util.CurrentThreadCaptureQueriesListener;
import net.ttddyy.dsproxy.listener.logging.SLF4JLogLevel;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.apache.commons.dbcp2.BasicDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.fail;

@Configuration
@Import({
	JpaR4Config.class,
	HapiJpaConfig.class,
	TestJPAConfig.class,
	TestHSearchAddInConfig.DefaultLuceneHeap.class,
	JpaBatch2Config.class,
	Batch2JobsConfig.class
})
public class TestR4WithDelayConfig extends TestR4Config {

	private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(TestR4WithDelayConfig.class);

	private final Deque<Exception> myLastStackTrace = new LinkedList<>();
	private boolean myHaveDumpedThreads;

	@Override
	@Bean
	public DataSource dataSource() {
		BasicDataSource retVal = new BasicDataSource() {

			@Override
			public Connection getConnection() {
				ConnectionWrapper retVal;
				try {
					retVal = new ConnectionWrapper(super.getConnection());
				} catch (Exception e) {
					ourLog.error("Exceeded maximum wait for connection (" + ourMaxThreads + " max)", e);
					logGetConnectionStackTrace();
					fail("Exceeded maximum wait for connection (" + ourMaxThreads + " max): " + e);
					retVal = null;
				}

				try {
					throw new Exception();
				} catch (Exception e) {
					synchronized (myLastStackTrace) {
						myLastStackTrace.add(e);
						while (myLastStackTrace.size() > ourMaxThreads) {
							myLastStackTrace.removeFirst();
						}
					}
				}

				return retVal;
			}

			private void logGetConnectionStackTrace() {
				StringBuilder b = new StringBuilder();
				int i = 0;
				synchronized (myLastStackTrace) {
					for (Iterator<Exception> iter = myLastStackTrace.descendingIterator(); iter.hasNext(); ) {
						Exception nextStack = iter.next();
						b.append("\n\nPrevious request stack trace ");
						b.append(i++);
						b.append(":");
						for (StackTraceElement next : nextStack.getStackTrace()) {
							b.append("\n   ");
							b.append(next.getClassName());
							b.append(".");
							b.append(next.getMethodName());
							b.append("(");
							b.append(next.getFileName());
							b.append(":");
							b.append(next.getLineNumber());
							b.append(")");
						}
					}
				}
				ourLog.info(b.toString());

				if (!myHaveDumpedThreads) {
					ourLog.info("Thread dump:" + crunchifyGenerateThreadDump());
					myHaveDumpedThreads = true;
				}
			}

		};

		retVal.setDriver(new org.h2.Driver());
		retVal.setUrl("jdbc:h2:mem:testdb_r4");
		retVal.setMaxWaitMillis(30000);
		retVal.setUsername("");
		retVal.setPassword("");
		retVal.setMaxTotal(ourMaxThreads);

		SLF4JLogLevel level = SLF4JLogLevel.INFO;
		DataSource dataSource = ProxyDataSourceBuilder
			.create(retVal)
			.logSlowQueryBySlf4j(10, TimeUnit.SECONDS, level)
			.beforeQuery(new BlockLargeNumbersOfParamsListener())
			.beforeQuery(getMandatoryTransactionListener())
			.afterQuery(captureQueriesListener())
			.afterQuery(new CurrentThreadCaptureQueriesListener())
			.afterQuery(delayListener())
			.countQuery(singleQueryCountHolder())
			.afterMethod(captureQueriesListener())
			.build();

		return dataSource;
	}

	@Bean
	DelayListener delayListener() {
		return new DelayListener();
	}

}
