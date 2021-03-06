/**
 * 
 */
package com.citusdata.migration;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBStreams;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBStreamsClientBuilder;
import com.amazonaws.services.dynamodbv2.model.ListTablesResult;
import com.citusdata.migration.datamodel.NonExistingTableException;
import com.citusdata.migration.datamodel.TableEmitter;
import com.citusdata.migration.datamodel.TableExistsException;

import javax.management.*;

/**
 * @author marco
 *
 */
public class DynamoDBReplicator implements DynamoDBReplicatorMBean {

	private static final Log LOG = LogFactory.getLog(DynamoDBReplicator.class);

	public static void main(String[] args) throws SQLException, IOException {
		Options options = new Options();

		Option help = new Option("h", "help", false, "Show help");
		help.setRequired(false);
		options.addOption(help);

		Option dynamoDBTableOption = new Option("t", "table", true, "DynamoDB table name(s) to replicate");
		dynamoDBTableOption.setRequired(false);
		options.addOption(dynamoDBTableOption);

		Option postgresURLOption = new Option("u", "postgres-jdbc-url", true, "PostgreSQL JDBC URL of the destination");
		postgresURLOption.setRequired(false);
		options.addOption(postgresURLOption);

		Option noSchemaOption = new Option("s", "schema", false, "Replicate the table schema");
		noSchemaOption.setRequired(false);
		options.addOption(noSchemaOption);

		Option noDataOption = new Option("d", "data", false, "Replicate the current data");
		noDataOption.setRequired(false);
		options.addOption(noDataOption);

		Option noChangesOption = new Option("c", "changes", false, "Continuously replicate changes");
		noChangesOption.setRequired(false);
		options.addOption(noChangesOption);

		Option citusOption = new Option("x", "citus", false, "Create distributed tables using Citus");
		citusOption.setRequired(false);
		options.addOption(citusOption);

		Option conversionModeOption = new Option("m", "conversion-mode", true, "Conversion mode, either columns or jsonb (default: columns)");
		conversionModeOption.setRequired(false);
		options.addOption(conversionModeOption);

		Option lowerCaseColumnsOption = new Option("lc", "lower-case-column-names", false, "Use lower case column names");
		lowerCaseColumnsOption.setRequired(false);
		options.addOption(lowerCaseColumnsOption);

		Option numConnectionsOption = new Option("n", "num-connections", true, "Database connection pool size (default 16)");
		numConnectionsOption.setRequired(false);
		options.addOption(numConnectionsOption);

		Option maxScanRateOption = new Option("r", "scan-rate", true, "Maximum reads/sec during scan (default 25)");
		maxScanRateOption.setRequired(false);
		options.addOption(maxScanRateOption);

		Option mfa = new Option("mfa", "mfa-code", true, "Mfa code");
		mfa.setRequired(false);
		options.addOption(mfa);

		Option mfaARN = new Option("mfa_arn", "mfa-arn", true, "Mfa ARN");
		mfaARN.setRequired(false);
		options.addOption(mfaARN);

		Option assumeRoleArn = new Option("role_arn", "role-arn", true, "Assume Role ARN");
		assumeRoleArn.setRequired(false);
		options.addOption(assumeRoleArn);

		Option region = new Option("region", "region", true, "Region");
		region.setRequired(false);
		options.addOption(region);

		Option schema = new Option("sn", "schema-name", true, "Schema name");
		schema.setRequired(false);
		options.addOption(schema);

		CommandLineParser parser = new DefaultParser();
		HelpFormatter formatter = new HelpFormatter();
		formatter.setWidth(120);

		try {
			CommandLine cmd = parser.parse(options, args);

			boolean wantHelp = cmd.hasOption("help");
			boolean replicateSchema = cmd.hasOption("schema");
			boolean replicateData = cmd.hasOption("data");
			boolean replicateChanges = cmd.hasOption("changes");

			if (wantHelp || (!replicateSchema && !replicateData && !replicateChanges)) {
				formatter.printHelp("podyn", options);
				return;
			}

			boolean useCitus = cmd.hasOption("citus");
			boolean useLowerCaseColumnNames = cmd.hasOption("lower-case-column-names");
			int maxScanRate = Integer.parseInt(cmd.getOptionValue("scan-rate", "25"));
			int dbConnectionCount = Integer.parseInt(cmd.getOptionValue("num-connections", "16"));
			String tableNamesString = cmd.getOptionValue("table");
			String postgresURL = cmd.getOptionValue("postgres-jdbc-url");
			String conversionModeString = cmd.getOptionValue("conversion-mode", ConversionMode.columns.name());

			ConversionMode conversionMode;

			try {
				conversionMode = ConversionMode.valueOf(conversionModeString);
			} catch (IllegalArgumentException e) {
				throw new ParseException("invalid conversion mode: " + conversionModeString);
			}

			AWSCredentialsProvider credentialsProvider;
			String mfaValue = cmd.getOptionValue("mfa", "");
			String mfaARNValue = cmd.getOptionValue("mfa_arn", "");
			String assumeRoleARNValue = cmd.getOptionValue("role_arn", "");
			String regionValue = cmd.getOptionValue("region", "");
			String schemaNameValue = cmd.getOptionValue("sn", "public");
			if (mfaValue.isEmpty()) {
				credentialsProvider = new DefaultAWSCredentialsProviderChain();
			} else {
				credentialsProvider = MyAWSCredentialProvider.getMFAAssumeRoleCredentialProvider(mfaARNValue, assumeRoleARNValue, regionValue, mfaValue);
			}

			AmazonDynamoDB dynamoDBClient = AmazonDynamoDBClientBuilder.standard().
					withCredentials(credentialsProvider).
					build();

			AmazonDynamoDBStreams streamsClient = AmazonDynamoDBStreamsClientBuilder.standard().
					withCredentials(credentialsProvider).
					build();

			final TableEmitter emitter;

			if (postgresURL != null) {
				List<TableEmitter> emitters = new ArrayList<>();

				for(int i = 0; i < dbConnectionCount; i++) {
					emitters.add(new JDBCTableEmitter(postgresURL, schemaNameValue));
				}

				emitter = new HashedMultiEmitter(emitters);
			} else {
				emitter = new StdoutSQLEmitter();
			}

			Runtime.getRuntime().addShutdownHook(new Thread() {
				public void run() {
					LOG.info("Closing database connections");
					emitter.close();
				}
			});

			List<DynamoDBTableReplicator> replicators = new ArrayList<>();

			List<String> tableNames = new ArrayList<>();

			if (tableNamesString != null) {
				tableNames = Arrays.asList(tableNamesString.split(","));
			} else {
				ListTablesResult listTablesResult = dynamoDBClient.listTables();
				for(String tableName : listTablesResult.getTableNames()) {
					if (!tableName.startsWith(DynamoDBTableReplicator.LEASE_TABLE_PREFIX)) {
						tableNames.add(tableName);
					}
				}
			}

			ExecutorService executor = Executors.newCachedThreadPool();

			for(String tableName : tableNames) {
				DynamoDBTableReplicator replicator = new DynamoDBTableReplicator(
						dynamoDBClient, streamsClient, credentialsProvider, executor, emitter, tableName);

				replicator.setAddColumnEnabled(true);
				replicator.setUseCitus(useCitus);
				replicator.setUseLowerCaseColumnNames(useLowerCaseColumnNames);
				replicator.setConversionMode(conversionMode);

				replicators.add(replicator);
			}

			if (replicateSchema) {
				for(DynamoDBTableReplicator replicator : replicators) {
					if (replicator.schemaExisted) continue;
					LOG.info(String.format("Constructing table schema for table %s", replicator.dynamoTableName));

					replicator.replicateSchema();
				}
			}

			if (replicateData) {
				List<Future<Long>> futureResults = new ArrayList<Future<Long>>();

				for(DynamoDBTableReplicator replicator : replicators) {
					if (replicator.schemaExisted) continue;
					LOG.info(String.format("Replicating data for table %s", replicator.dynamoTableName));
					Future<Long> futureResult = replicator.startReplicatingData(maxScanRate);
					futureResults.add(futureResult);
				}

				for(Future<Long> futureResult : futureResults) {
					futureResult.get();
				}
			}

			if (replicateChanges) {
				for(DynamoDBTableReplicator replicator : replicators) {
					LOG.info(String.format("Replicating changes for table %s", replicator.dynamoTableName));
					replicator.startReplicatingChanges();
				}
			} else {
				executor.shutdown();
			}

			registerMBean();

		} catch (ParseException e) {
			LOG.error(e.getMessage(), e);
			formatter.printHelp("podyn", options);
			System.exit(3);
		} catch (TableExistsException|NonExistingTableException e) {
			LOG.error(e.getMessage(), e);
			System.exit(1);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();

			if (cause.getCause() != null) {
				LOG.error(cause.getCause().getMessage(), e);
			} else {
				LOG.error(cause.getMessage(), e);
			}
			System.exit(1);
		} catch (EmissionException e) {
			if (e.getCause() != null) {
				LOG.error(e.getCause().getMessage(), e);
			} else {
				LOG.error(e.getMessage(), e);
			}
			System.exit(1);
		} catch (RuntimeException e) {
			if (e.getCause() != null) {
				LOG.error(e.getCause().getMessage(), e);
			} else {
				LOG.error(e.getMessage(), e);
			}
			System.exit(2);
		} catch (Exception e) {
			LOG.error(e);
			System.exit(1);
		}
	}

	static void registerMBean() throws MalformedObjectNameException, NotCompliantMBeanException, InstanceAlreadyExistsException, MBeanRegistrationException {
		MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
		ObjectName mbeanName = new ObjectName("com.citusdata.migration:type=DynamoDBReplicator");
		DynamoDBReplicator mbean = new DynamoDBReplicator();
		mbs.registerMBean(mbean, mbeanName);
	}
}
