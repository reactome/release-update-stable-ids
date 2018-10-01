package org.reactome.release.downloadDirectory;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.gk.model.GKInstance;
import org.gk.model.InstanceUtilities;
import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.MySQLAdaptor;

public class OutputCuratedComplexInstances {

	public static void execute(MySQLAdaptor dba) throws Exception {
		
		// Get all Complex instances that have been manually curated by specifying all with the 'inferredFrom' attribute set to null
		Collection<GKInstance> complexInstances = dba.fetchInstanceByAttribute("Complex", "inferredFrom", "IS NULL", "null");
		
		// Sort by display name
		ArrayList<String> complexDisplayNames = new ArrayList<String>();
		HashMap<String,GKInstance> sortedComplexInstances = new HashMap<String,GKInstance>();
		for (GKInstance complexInst : complexInstances) {
			complexDisplayNames.add((complexInst.getAttributeValue(ReactomeJavaConstants._displayName).toString()));
			sortedComplexInstances.put(complexInst.getAttributeValue(ReactomeJavaConstants._displayName).toString(), complexInst);
		}
		Collections.sort(complexDisplayNames);
		
		// There are two output files for this module 'curated_complexes.txt' and 'curated_complexes.stid.txt'
		// The only difference between them is the addition of a stable id column for the second file.
		// Files are generated by populating ArrayLists with the lines of the file
		ArrayList<String> complexLines = new ArrayList<String>();
		ArrayList<String> stidComplexLines = new ArrayList<String>();
		
		// TODO: Small file differences (GKB::Utils#13-48-1362)
		
		// Add headers as first line
		complexLines.add("#DB_ID\t#species.name[0]\t#_displayName\n");
		stidComplexLines.add("#DB_ID\t#species.name[0]\t#_displayName\n");
		
		// Iterate through all retrieved instances
		for (String displayName : complexDisplayNames) {
			GKInstance complexInst = sortedComplexInstances.get(displayName);		
			String complexLine = "";
			String stidComplexLine = "";
			
			// Add stable id as first column of stable id file
			stidComplexLine += ((GKInstance) complexInst.getAttributeValue(ReactomeJavaConstants.stableIdentifier)).getDisplayName() + "\t";
			// Add DB ID
			complexLine += complexInst.getDBID().toString() + "\t";
			stidComplexLine += complexInst.getDBID().toString() + "\t";
			
			// Check that species attributes exist, and then add the display name to the line if it exists. Otherwise, just add a tab.
			// TODO: Pipe separators for multi-species instances
			if ((complexInst.getAttributeValue(ReactomeJavaConstants.species) != null && ((GKInstance) complexInst.getAttributeValue(ReactomeJavaConstants.species)).getDisplayName() != null)) {
				Collection<GKInstance> speciesInstances = complexInst.getAttributeValuesList(ReactomeJavaConstants.species);
				List<String> speciesNames = new ArrayList<String>();
				for (GKInstance speciesInst : speciesInstances) {
					speciesNames.add(speciesInst.getDisplayName());
				}
				complexLine += String.join("|", speciesNames) + "\t";
				stidComplexLine += String.join("|", speciesNames) + "\t";
			} else {
				complexLine += "\t";
				stidComplexLine += "\t";
			}
			
			// Final column for each file the Complexes display name
			complexLine += complexInst.getDisplayName() + "\n";
			stidComplexLine += complexInst.getDisplayName() + "\n";
			
			//Add completed line to associated array
			complexLines.add(complexLine);
			stidComplexLines.add(stidComplexLine);
		}
		// Generate 'curated_complexes.txt' file
		String curatedComplexesFilename = "curated_complexes.txt";
		PrintWriter curatedComplexFile = new PrintWriter(curatedComplexesFilename);
		for (String complexLine : complexLines) {
			Files.write(Paths.get(curatedComplexesFilename), complexLine.getBytes(), StandardOpenOption.APPEND);
		}
		curatedComplexFile.close();	
		
		// Generate 'curated_complexes.stid.txt' file
		String stidCuratedComplexFilename = "curated_complexes.stid.txt";
		PrintWriter stidCuratedComplexFile = new PrintWriter(stidCuratedComplexFilename);
		for (String stidComplexLine : stidComplexLines) {
			Files.write(Paths.get(stidCuratedComplexFilename), stidComplexLine.getBytes(), StandardOpenOption.APPEND);
		}
		stidCuratedComplexFile.close();
		
	}
}
