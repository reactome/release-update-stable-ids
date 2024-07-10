package org.reactome.release.update_stable_ids;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gk.persistence.MySQLAdaptor;

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

		MySQLAdaptor dbaSlice = getCurrentSliceDBA(props);
		MySQLAdaptor dbaPrevSlice = getPreviousSliceDBA(props);
		MySQLAdaptor dbaGkCentral = getCuratorDBA(props);
		long personId = Long.parseLong(props.getProperty("personId"));

		StableIdentifierUpdater.updateStableIdentifiers(dbaSlice, dbaPrevSlice, dbaGkCentral, personId);

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

	private static MySQLAdaptor getCurrentSliceDBA(Properties props) throws SQLException {
		String databaseNameProperty = "slice_current.name";

		return getReleaseDBA(props, databaseNameProperty);
	}

	private static MySQLAdaptor getPreviousSliceDBA(Properties props) throws SQLException {
		String databaseNameProperty = "slice_previous.name";

		return getReleaseDBA(props, databaseNameProperty);
	}

	private static MySQLAdaptor getReleaseDBA(Properties props, String databaseNameProperty) throws SQLException {
		final String propertyPrefix = "release.database";

		return getDBA(props, propertyPrefix, databaseNameProperty);
	}

	private static MySQLAdaptor getCuratorDBA(Properties props) throws SQLException {
		final String propertyPrefix = "curator.database";
		final String databaseNameProperty = propertyPrefix + ".name";

		return getDBA(props, propertyPrefix, databaseNameProperty);
	}

	private static MySQLAdaptor getDBA(Properties props, String propertyPrefix, String databaseNameProperty)
		throws SQLException {

		String userName = props.getProperty(propertyPrefix + ".user");
		String password = props.getProperty(propertyPrefix + ".password");
		String host = props.getProperty(propertyPrefix + ".host");
		String databaseName = props.getProperty(databaseNameProperty);
		int port = Integer.parseInt(props.getProperty(propertyPrefix + ".port"));

		return new MySQLAdaptor(host, databaseName, userName, password, port);
	}
}
