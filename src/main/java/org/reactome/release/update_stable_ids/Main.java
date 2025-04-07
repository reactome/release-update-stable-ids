package org.reactome.release.update_stable_ids;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.reactome.server.service.persistence.Neo4JAdaptor;

/**
 * This function iterates through all instances, and checks if it has been changed since the previous release.
 * Instances that have been changed have their 'identifierVersion' attribute incremented by 1 to reflect a change.
 *
 */
public class Main {
	private static final Logger logger = LogManager.getLogger();

	public static void main(String[] args) throws Exception {
		logger.info("Beginning UpdateStableIds step...");

		Properties props = getProperties(getPathToConfigFile(args));

		Neo4JAdaptor dbaSlice = getCurrentSliceDBA(props);
		Neo4JAdaptor dbaPrevSlice = getPreviousSliceDBA(props);
		Neo4JAdaptor dbaGkCentral = getCuratorDBA(props);
		long personId = Long.parseLong(props.getProperty("personId"));

		StableIdentifierUpdater stableIdentifierUpdater = new StableIdentifierUpdater(dbaSlice, dbaPrevSlice, dbaGkCentral, personId);
		stableIdentifierUpdater.update();

		logger.info("Finished UpdateStableIds step");
	}

	private static Properties getProperties(Path pathToConfigFile) throws IOException {
		Properties props = new Properties();
		props.load(Files.newInputStream(pathToConfigFile));
		return props;
	}

	private static Path getPathToConfigFile(String[] args) {
		String pathToConfigFile;
		if (args.length > 0) {
			pathToConfigFile = args[0];
		} else {
			pathToConfigFile = "src/main/resources/config.properties";
		}

		return Paths.get(pathToConfigFile);
	}

	private static Neo4JAdaptor getCurrentSliceDBA(Properties props) throws SQLException {
		String databaseNameProperty = "slice_current.name";

		return getReleaseDBA(props, databaseNameProperty);
	}

	private static Neo4JAdaptor getPreviousSliceDBA(Properties props) throws SQLException {
		String databaseNameProperty = "slice_previous.name";

		return getReleaseDBA(props, databaseNameProperty);
	}

	private static Neo4JAdaptor getReleaseDBA(Properties props, String databaseNameProperty) throws SQLException {
		final String propertyPrefix = "release.database";

		return getDBA(props, propertyPrefix, databaseNameProperty);
	}

	private static Neo4JAdaptor getCuratorDBA(Properties props) throws SQLException {
		final String propertyPrefix = "curator.database";
		final String databaseNameProperty = propertyPrefix + ".name";

		return getDBA(props, propertyPrefix, databaseNameProperty);
	}

	private static Neo4JAdaptor getDBA(Properties props, String propertyPrefix, String databaseNameProperty) {

		String userName = props.getProperty(propertyPrefix + ".user");
		String password = props.getProperty(propertyPrefix + ".password");
		String host = props.getProperty(propertyPrefix + ".host");
		String databaseName = props.getProperty(databaseNameProperty);
		int port = Integer.parseInt(props.getProperty(propertyPrefix + ".port"));

		String uri = String.format("bolt://%s:%d", host, port);
		return new Neo4JAdaptor(uri, userName, password, databaseName);
	}
}
