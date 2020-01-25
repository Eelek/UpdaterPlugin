package tk.projectcraftmc.updater;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.json.simple.parser.ParseException;

public class UpdaterMain extends JavaPlugin {
	
	Mapper mapper;
	Watchdog watchdog;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		getConfig().options().copyDefaults(true);

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

	public boolean onCommand(CommandSender sender, Command cmd, String commandlabel, String[] args) {
		if (cmd.getName().equalsIgnoreCase("genmap")) {
			if (sender instanceof Player) {
				Player p = (Player) sender;
				watchdog.registerChunk(p.getLocation().getBlock());
				
				try {
					mapper.updateMap();
				} catch (IOException | ParseException e) {
					e.printStackTrace();
				}
			} else {
				sender.sendMessage("player only");
			}
		}
		
		if(cmd.getName().equalsIgnoreCase("genarea")) {
			if(args.length == 5) {
				try {
					mapper.generateArea(getServer().getWorld(args[0]), Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4]));
				} catch(NumberFormatException e) {
					e.printStackTrace();
				}
			} else {
				sender.sendMessage("Use /genarea <world> <startX> <startZ> <endX> <endZ>");
			}
		}

		return false;
	}
}
	