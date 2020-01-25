package tk.projectcraftmc.updater;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;

import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

public class Watchdog implements Listener {
	
	private UpdaterMain plugin;
	private ArrayList<LightChunk> chunks;
	private Runnable watchdog;
	
	public Watchdog(UpdaterMain instance) {
		this.plugin = instance;
		this.chunks = new ArrayList<LightChunk>();
		this.watchdog = new Runnable() {
			
			@Override
			public void run() {
				try {
					saveChunkCache();
				} catch(Exception e) {
					plugin.getLogger().severe("An error occured whilst trying to update chunk cache.");
					e.printStackTrace();
				}
			}
		};
		
		long delaytime = plugin.getConfig().getInt("memory-clean-update-time") * 20L;
		plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, watchdog, delaytime, delaytime);
	}
	
	@EventHandler (priority = EventPriority.MONITOR)
	public void onBlockBreak(BlockBreakEvent e) {
		registerChunk(e.getBlock());
	}
	
	@EventHandler (priority = EventPriority.MONITOR)
	public void onBlockPlace(BlockPlaceEvent e) {
		registerChunk(e.getBlock());
	}
	
	public void registerChunk(Block b) {
		LightChunk c = new LightChunk(b.getWorld(), b.getChunk().getX(), b.getChunk().getZ());
		if (!chunks.contains(c)) {
			chunks.add(c);
		}
	}
	
	public ArrayList<LightChunk> getEditedChunks() throws FileNotFoundException, IOException, ParseException {
		ArrayList<LightChunk> editedChunks = new ArrayList<LightChunk>();
		
		JSONArray cacheJSON = getChunkCache();
		for(int c = 0; c < cacheJSON.size(); c++) {
			JSONObject chunk = (JSONObject) cacheJSON.get(c);
			editedChunks.add(LightChunk.deserialize(chunk));
		}
		
		ArrayList<LightChunk> copy = chunks;
		for(LightChunk c : copy) {
			if(editedChunks.contains(c)) continue;
			
			editedChunks.add(c);
		}
		
		return editedChunks;
	}
	
	@SuppressWarnings("unchecked")
	public void saveChunkCache() throws IOException, ParseException {
		if(chunks.isEmpty()) return;
		
		plugin.getServer().broadcastMessage("Writing memory to file. This may be laggy.");

		JSONArray cacheJSON = getChunkCache();
		ArrayList<LightChunk> copy = chunks;

		for (LightChunk c : copy) {
		    JSONObject chunk = c.serialize();
		    
		    if(cacheJSON.contains(c)) return;
		    
		    cacheJSON.add(chunk);
		}

		writeChunkCache(cacheJSON, true);

		chunks.removeAll(copy);
		
		System.gc();
		
		plugin.getServer().broadcastMessage("Memory clear complete.");
	}
	
	public void clearChunkCache() throws IOException {
		writeChunkCache(new JSONArray(), false);
		chunks.clear();
	}
	
	private JSONArray getChunkCache() throws FileNotFoundException, IOException, ParseException {
		JSONParser parser = new JSONParser();

		Object file = parser.parse(new FileReader(plugin.getDataFolder() + "/chunkCache.json"));
		
		return (JSONArray) file;
	}
	
	private void writeChunkCache(JSONArray data, boolean append) throws IOException {
		FileWriter cacheFile = new FileWriter(plugin.getDataFolder() + "/chunkCache.json", append);
		cacheFile.write(data.toJSONString());
		cacheFile.close();
	}
	
}