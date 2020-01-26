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
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
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
		
		if (!(new File(getDataFolder() + "/chunkCache.json")).exists()) {
			saveResource("chunkCache.json", false);
		}
		
		if (!(new File(getDataFolder() + "/BlockMapColors.json")).exists()) {
			saveResource("BlockMapColors.json", false);
		}
		
		fetchSetupData();

		mapper = new Mapper(this);
		watchdog = new Watchdog(this);

		getServer().getPluginManager().registerEvents(watchdog, this);
	}

	public void onDisable() {
		getServer().getScheduler().cancelTasks(this);
		mapper = null;
		watchdog = null;
	}

	public void sendDataToWebserver(String data, String URL) throws IOException {
		URL serverURL = new URL(URL + "?API-key=" + getConfig().getString("api-key"));
		HttpsURLConnection conn = (HttpsURLConnection) serverURL.openConnection();

		conn.setRequestMethod("POST");

		conn.setRequestProperty("User-Agent", "Mozilla/5.0");

		conn.setDoInput(true);
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
		URL serverURL = new URL(URL + "?API-key=" + getConfig().getString("api-key"));
		HttpsURLConnection conn = (HttpsURLConnection) serverURL.openConnection();

		conn.setRequestMethod("GET");
		conn.setRequestProperty("User-Agent", "Mozilla/5.0");
		conn.setDoInput(true);
		conn.setDoOutput(true);

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
	
	private void fetchSetupData() {
		try {
			JSONParser parser = new JSONParser();
			
			JSONObject apidata = (JSONObject) parser.parse(getDataFromWebserver(getConfig().getString("api-fetch-url")));
			
			COMPRESSION = (int) Math.pow(2, Integer.parseInt(apidata.get("compression").toString()) - 1);
			CHUNKSIZE = Integer.parseInt(apidata.get("chunkSize").toString());
		} catch (ParseException | IOException e) {
			getLogger().severe("An error occured whilst initiating the Mapper.");
			e.printStackTrace();
		}
	}
	
	public boolean onCommand(CommandSender sender, Command cmd, String commandlabel, String[] args) {
		if(cmd.getName().equalsIgnoreCase("updatemap")) {
			try {
				mapper.updateMap();
			} catch (IOException | ParseException e) {
				e.printStackTrace();
			}
		}
		
		if(cmd.getName().equalsIgnoreCase("setcompression")) {
			this.COMPRESSION = (int) Math.pow(2, Integer.parseInt(args[0]) - 1);
			sender.sendMessage("done");
		}
		
		if(cmd.getName().equalsIgnoreCase("clearcache")) {
			try {
				watchdog.clearChunkCache();
			} catch (IOException e) {
				e.printStackTrace();
			}
			sender.sendMessage("cleared cache");
		}
		
		return false;
	}
}
	