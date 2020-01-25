package tk.projectcraftmc.updater;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

public class UpdaterMain extends JavaPlugin {
	
	Mapper mapper;
	Watchdog watchdog;
	
	int COMPRESSION;
	int CHUNKSIZE;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		getConfig().options().copyDefaults(true);
		
		fetchSetupData();

		mapper = new Mapper(this);
		watchdog = new Watchdog(this);

		getServer().getPluginManager().registerEvents(watchdog, this);
		
		if (!(new File(getDataFolder() + "/chunkCache.json")).exists()) {
			saveResource("chunkCache.json", false);
		}
	}

	public void onDisable() {
		getServer().getScheduler().cancelTasks(this);
		mapper = null;
		watchdog = null;
	}

	public void sendDataToWebserver(String data, String URL) throws IOException {
		data += "&API-key=" + getConfig().getString("api-key");
		
		URL serverURL = new URL(URL);
		HttpsURLConnection conn = (HttpsURLConnection) serverURL.openConnection();

		conn.setRequestMethod("POST");

		conn.setRequestProperty("User-Agent", "Mozilla/5.0");

		conn.setDoOutput(true);

		DataOutputStream out = new DataOutputStream(conn.getOutputStream());

		out.writeBytes(data);

		out.flush();

		out.close();
		
		if(conn.getResponseCode() > 200) {
			throw new ConnectException("Couldn't send data to the webserver. (" + conn.getResponseCode() + " " + conn.getResponseMessage() + ")");
		}

		conn.disconnect();
	}

	public String getDataFromWebserver(String URL) throws IOException {
		URL serverURL = new URL(URL);
		HttpsURLConnection conn = (HttpsURLConnection) serverURL.openConnection();

		conn.setRequestMethod("GET");
		conn.setRequestProperty("User-Agent", "Mozilla/5.0");

		if (conn.getResponseCode() > 200) {
			throw new ConnectException("Couldn't get data from the webserver. (" + conn.getResponseCode() + " " + conn.getResponseMessage() + ")");
		}

		String input = "";

		BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));

		StringBuffer response = new StringBuffer();
		
		while((input = in.readLine()) != null) {
			response.append(input);
		}

		in.close();

		return response.toString();
	}
	
	public SuperChunk getLightChunk(World w, int x, int z) {
		x = Math.floorDiv(x * CHUNKSIZE, CHUNKSIZE);
		z = Math.floorDiv(z * CHUNKSIZE, CHUNKSIZE);
		
		return new SuperChunk(w, x, z);
	}
	
	private void fetchSetupData() {
		try {
			JSONParser parser = new JSONParser();
			
			JSONObject apidata = (JSONObject) parser.parse(getDataFromWebserver(getConfig().getString("api-fetch-url")));
			
			COMPRESSION = Integer.parseInt(apidata.get("compression").toString());
			CHUNKSIZE = Integer.parseInt(apidata.get("chunkSize").toString());
		} catch (ParseException | IOException e) {
			getLogger().severe("An error occured whilst initiating the Mapper.");
			e.printStackTrace();
		}
	}
}
	