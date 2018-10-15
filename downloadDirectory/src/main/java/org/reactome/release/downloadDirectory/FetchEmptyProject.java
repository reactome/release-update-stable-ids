package org.reactome.release.downloadDirectory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.gk.persistence.MySQLAdaptor;

public class FetchEmptyProject {
	private static Connection connect = null;
	private static Statement statement = null;
	private PreparedStatement preparedStatement = null;
	private static ResultSet resultSet = null;
	public static void execute(MySQLAdaptor dba, String username, String password) throws SQLException, ClassNotFoundException, UnsupportedEncodingException, FileNotFoundException, IOException {
		
		Class.forName("com.mysql.jdbc.Driver");
		connect = DriverManager.getConnection("jdbc:mysql://localhost/test_reactome_66_final?" + "user=" + username + "&password=" + password);
		statement = connect.createStatement();
		resultSet = statement.executeQuery("SELECT ontology FROM Ontology");
		
		PrintWriter pprjWriter = new PrintWriter("reactome_data_model.pprj");
		
		// The returned value is a single blob composed of binary and text. The three files produced by this step (pprj, pins, pont) are found within this blob.
		// A handful of regexes and conditional statements are used to handle this data and output the 3 files. 
		while (resultSet.next()) {
			
			Blob blob = resultSet.getBlob("ontology");
			InputStream is = blob.getBinaryStream();
			BufferedReader br = new BufferedReader(new InputStreamReader(is));

			String dateTime = "";
			int dateTimeCounter = 0;
			boolean pprjSwitch = true;
			String str;
			while ((str = br.readLine()) != null) {
				
				// A very specific regex for matching a datetime string -- Could probably be matched to a specific format that looks cleaner
				String[] splitLine = str.split(";");
				if (splitLine.length > 1) {
					if (splitLine[1].matches("( [A-Z][a-z]{2}){2} [0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2} [A-Z]{3} [0-9]{4}")) {
						dateTime = ";" + splitLine[1] + "\n";
						dateTimeCounter++;
						if (pprjSwitch) {
							Files.write(Paths.get("reactome_data_model.pprj"), dateTime.getBytes(), StandardOpenOption.APPEND);
						}
						continue;
					}
				}
				str += "\n";
				
				// Generate pprj file
				if (dateTimeCounter == 1 && pprjSwitch) {
					if (str.contains("pprj_file_content")) {
						str = "\n";
						pprjSwitch = false;
					}
					if (str.contains(".pont")) {
						str = str.replaceAll("[a-zA-Z0-9]+.pont", "reactome_data_model.pont");
					}
					if (str.contains(".pins")) {
						str = str.replaceAll("[a-zA-Z0-9]+.pins", "reactome_data_model.pins");
					}
					Files.write(Paths.get("reactome_data_model.pprj"), str.getBytes(), StandardOpenOption.APPEND);
				}
			}
		}
		pprjWriter.close();
	}
}
