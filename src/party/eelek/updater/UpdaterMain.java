package party.eelek.updater;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.json.simple.parser.ParseException;

public class UpdaterMain extends JavaPlugin {
	
	Mapper mapper;
	Watchdog watchdog;
	
	int CHUNKSIZE;
	
	boolean updating = false;
	boolean debugLogging = false;
	boolean broadcast = true;
	
	final String PREFIX = "" + ChatColor.DARK_BLUE + ChatColor.BOLD + "[Map Updater] ";

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

		mapper = new Mapper(this);
		watchdog = new Watchdog(this);

		getServer().getPluginManager().registerEvents(watchdog, this);
		
		debugLogging = getConfig().getBoolean("logging");
		broadcast = getConfig().getBoolean("broadcast");
	}

	public void onDisable() {
		getServer().getScheduler().cancelTasks(this);
		
		try {
			watchdog.saveChunkCache();
		} catch (IOException | ParseException e) {
			e.printStackTrace();
			getLogger().severe("There was an error whilst saving the chunk cache.");
		}
		
		mapper = null;
		watchdog = null;
	}

	public void sendDataToWebserver(String data, String URL) throws IOException, ConnectException {
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
		
		if(conn.getResponseCode() >= HttpsURLConnection.HTTP_BAD_REQUEST) {
			throw new ConnectException("Couldn't send data to the webserver. (" + conn.getResponseCode() + " " + conn.getResponseMessage() + ")");
		}

		conn.disconnect();
	}

	public String getDataFromWebserver(String URL) throws IOException, ConnectException {
		URL serverURL = new URL(URL + "?API-key=" + getConfig().getString("api-key"));
		HttpsURLConnection conn = (HttpsURLConnection) serverURL.openConnection();

		conn.setRequestMethod("GET");
		conn.setRequestProperty("User-Agent", "Mozilla/5.0");
		conn.setDoInput(true);
		conn.setDoOutput(true);

		if (conn.getResponseCode() >= HttpsURLConnection.HTTP_BAD_REQUEST) {
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
	
	public boolean onCommand(CommandSender sender, Command cmd, String commandlabel, String[] args) {
		if(cmd.getName().equalsIgnoreCase("updatemap")) {
			getServer().getScheduler().runTaskAsynchronously(this, new Runnable() {
				public void run() {
					try {
						mapper.updateMap(true);
						if(!broadcast && !debugLogging) sender.sendMessage(PREFIX + ChatColor.GREEN + "Map Updated.");
					} catch (IOException | ParseException e) {
						e.printStackTrace();
					}
				}
			});
			return true;
		}
		
		if(cmd.getName().equalsIgnoreCase("clearcache")) {
			try {
				watchdog.clearChunkCache();
				sender.sendMessage(PREFIX + ChatColor.GREEN + "Cleared the chunk cache.");
			} catch (IOException e) {
				e.printStackTrace();
			}
			return true;
		}
		
		if(cmd.getName().equalsIgnoreCase("loadmap")) {
			if(args.length == 4) {
				int startX = Integer.parseInt(args[0]);
				int startZ = Integer.parseInt(args[1]);
				int endX = Integer.parseInt(args[2]);
				int endZ = Integer.parseInt(args[3]);
				
				if(startX > endX) {
					int tmp = endX;
					endX = startX;
					startX = tmp;
				}
				
				if(startZ > endZ) {
					int tmp = endZ;
					endZ = startZ;
					startZ = tmp;
				}
				
				for(int z = startZ; z < endZ; z++) {
					for(int x = startX; x < endX; x++) {
						watchdog.registerBlock(x, z);
					}
				}
				
				sender.sendMessage(PREFIX + ChatColor.GREEN + "Registered (" + startX + ", " + startZ + ") to (" + endX + ", " + endZ + ").");
				return true;
			} else {
				sender.sendMessage(PREFIX + ChatColor.RED + "Use /loadmap <startX> <startZ> <endX> <endZ>.");
				return true;
			}
		}
		
		return false;
	}
}
	