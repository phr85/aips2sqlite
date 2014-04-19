package com.maxl.java.aips2sqlite;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

public class Interactions {

	private String m_language;
	private File m_db_file;
	private Connection conn;
	private Statement stat;
	private PreparedStatement prep;

	private int batch_cnt;
	
	private String table() {
		return "(_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
				"atc1 TEXT, name1 TEXT, atc2 TEXT, name2 TEXT, content TEXT);";
	}
	
	public String getDBFile() {
		return m_db_file.getAbsolutePath();
	}
	
	// Map ATC1 to List(ATC2, Info, Mechanismus, Effekt, Massnahmen, Grad) 
	private static Map<String, ArrayList<String>> m_drug_interactions_map;
	
	/**
	 * Constructors
	 */
	public Interactions(String language) {
		m_language = language;
		m_drug_interactions_map = new TreeMap<String, ArrayList<String>>();
	}
	
	/**
	 * Extract drug interactions from EPha file
	 */
	private void extract() {
		try {			
			// Load EPha interactions file	
			FileInputStream drugInteractionsCsv = new FileInputStream(Constants.FILE_INTERACTIONS_CSV);	
			BufferedReader br = new BufferedReader(new InputStreamReader(drugInteractionsCsv, "UTF-8"));
			String line;
			while ((line = br.readLine()) != null) {	 
				// A semicolon is used as a separator -> extract only ATC1
				String[] inter = line.split(";");	 
				String atc1_key = inter[0].substring(1, inter[0].length()-1);
				String entry = "";
				for (int k=1; k<9; ++k)
					entry += (inter[k].substring(1, inter[k].length()-1) + ";");
				entry = entry.substring(0, entry.length()-1);
	
				if (atc1_key!=null) {
					ArrayList<String> interaction = m_drug_interactions_map.get(atc1_key);
					if (interaction==null)
						interaction = new ArrayList<String>();				
					interaction.add(entry);
					
					m_drug_interactions_map.put(atc1_key, interaction);					
				}
			}
			br.close();
			
			// Remove entry with key "ATC1" (first line)
			m_drug_interactions_map.remove("TC");
	
		} catch (Exception e) {
			System.err.println(">> InteractionsDB: Error in processing file!");
		}	
	}	
	
	public void generateSqlDatabase() {
		
		long startTime = System.currentTimeMillis();
		if (CmlOptions.SHOW_LOGS)
			System.out.print("- Processing EPha drug interactions csv... ");
		
		// Get drug interactions from file
		extract();
		
		// Save interactions to DB
		try {		
			createDB();
			int intercnt = 0;
			for (Map.Entry<String, ArrayList<String>> entry : m_drug_interactions_map.entrySet()) {
			    String key = entry.getKey().toUpperCase();
			    ArrayList<String> value = entry.getValue();
			    System.out.println(++intercnt + ": " + key + " interacts with " + value.size() + " meds");
			    
			    /*
			    	key: ATC1 
			    	inter0: Name1 
			    	inter1: ATC2 
			    	inter2: Name2
			    	inter3: Info 
			    	inter4: Mechanismus 
			    	inter5: Effekt 
			    	inter6: Massnahmen 
			    	inter7: Grad
			    */
			    
			    /*
			     Risikoklassen
			     -------------
				     A: Keine Massnahmen notwendig (grün)
				     B: Vorsichtsmassnahmen empfohlen (gelb)
				     C: Regelmässige Überwachung (orange)
				     D: Kombination vermeiden (pinky)
				     X: Kontraindiziert (hellrot)
				     0: Keine Angaben (grau)
			    */
			    for (String s : value) {
			    	String[] inter = s.split(";");
			    	String risk_class = "";
			    	if (inter[7].equals("A")) 
			    		risk_class = "Keine Massnahmen notwendig";
			    	else if (inter[7].equals("B"))
			    		risk_class = "Vorsichtsmassnahmen empfohlen";
			    	else if (inter[7].equals("C"))
			    		risk_class = "Regelmässige Überwachung";
			    	else if (inter[7].equals("D"))
			    		risk_class = "Kombination vermeiden";
			    	else if (inter[7].equals("X"))
			    		risk_class = "Kontraindiziert";
			    	else if (inter[7].equals("0"))
			    		risk_class = "Keine Angaben";
			    				    	
			    	String para_class = "paragraph" + inter[7];			    	
			    	String html_content = "<div>" 
			    			+ "<div class=\"" + para_class + "\" id=\"" + key + " - " + inter[1] + "\">"
			    			+ "<div class=\"absTitle\">" + key + " [" + inter[0] + "] &rarr; " + inter[1] + " [" + inter[2] + "]</div></div>"
			    			+ "<p class=\"spacing2\">" + "<i>Risikoklasse:</i> " + risk_class + " (" + inter[7] + ")</p>"
			    			+ "<p class=\"spacing2\">" + "<i>Möglicher Effekt:</i> " + inter[3] + "</p>"
							+ "<p class=\"spacing2\">" + "<i>Mechanismus:</i> " + inter[4] + "</p>"
							+ "<p class=\"spacing2\">" + "<i>Empfohlene Massnahmen:</i> " + inter[6] + "</p></div>";			    	
			    	addDB(key, inter[0], inter[1], inter[2], html_content);
			    }
		        // Assume batch size of 20
		        if (batch_cnt>20) {
		        	conn.setAutoCommit(false);
		        	prep.executeBatch();
		        	conn.setAutoCommit(true);
		        	batch_cnt = 0;
		        }
		        batch_cnt++;
			}
			// Add the rest
        	conn.setAutoCommit(false);
        	prep.executeBatch();
        	conn.setAutoCommit(true);			
        	// Compress
	        stat.executeUpdate("VACUUM;");
			
		} catch (SQLException e ) {
			System.out.println("SQLException!");
		} catch (ClassNotFoundException e) {
			System.out.println("ClassNotFoundException!");
		}
				
		long stopTime = System.currentTimeMillis();
		if (CmlOptions.SHOW_LOGS) {
			System.out.println("processed " + m_drug_interactions_map.size() + " drug interactions in "
					+ (stopTime - startTime) / 1000.0f + " sec");
		}	
	}
	
	private void createDB() throws ClassNotFoundException, SQLException {		
		// Initializes org.sqlite.JDBC driver
		Class.forName("org.sqlite.JDBC");

		try {
			// Touch db file if it does not exist
			String db_url = System.getProperty("user.dir") + "/output/drug_interactions_idx_" + m_language + ".db";			
			File db_file = new File(db_url);
			if (!db_file.exists()) {
				db_file.getParentFile().mkdirs();
				db_file.createNewFile();
			}
			m_db_file = db_file;
			// Creates connection
			conn = DriverManager.getConnection("jdbc:sqlite:" + db_url);		
			stat = conn.createStatement();
			
	        // Create android metadata table
			stat.executeUpdate("DROP TABLE IF EXISTS inter_metadata;");
	        stat.executeUpdate("CREATE TABLE inter_metadata (locale TEXT default 'en_US');"); 	
	        stat.executeUpdate("INSERT INTO inter_metadata VALUES ('en_US');");	        

			// Create SQLite database
	        stat.executeUpdate("DROP TABLE IF EXISTS interactionsdb;");
	        stat.executeUpdate("CREATE TABLE interactionsdb " + table());
	        
	        // Create indices
	        stat.executeUpdate("CREATE INDEX idx_atc1 ON interactionsdb(atc1);");
	        stat.executeUpdate("CREATE INDEX idx_name1 ON interactionsdb(name1);");
	        stat.executeUpdate("CREATE INDEX idx_atc2 ON interactionsdb(atc2);");
	        stat.executeUpdate("CREATE INDEX idx_name2 ON interactionsdb(name2);");	        
	        stat.executeUpdate("CREATE INDEX idx_content ON interactionsdb(content);");	       
	        // 
	        prep = conn.prepareStatement("INSERT INTO interactionsdb VALUES (null, ?, ?, ?, ?, ?);");	       			           
		} catch (IOException e) {
			System.err.println(">> InteractionsDB: DB file does not exist!");
		} catch (SQLException e ) {
			System.err.println(">> InteractionsDB: SQLException!");
		} 
	}
	
	private void addDB(String atc1, String name1, String atc2, String name2, 
			String content) throws SQLException {
		if (prep!=null) {
			prep.setString(1, atc1);
			prep.setString(2, name1);
			prep.setString(3, atc2);
			prep.setString(4, name2);			
	        prep.setString(5, content);
	        prep.addBatch();
		} else {
			System.out.println(">> InteractionsDB: There is no database!");
			System.exit(0);
		}			
	}
}

