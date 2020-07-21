package party.eelek.updater;

import java.awt.Color;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.scheduler.BukkitTask;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

@SuppressWarnings("unchecked")
public class Mapper {
	
	private UpdaterMain plugin;
	private Runnable mapper;
	private BukkitTask mapperTask;
	
	private HashMap<Material, Color> mapColors;
	private ArrayList<Color> colorIndexes;
	private final int[] colorVariations = new int[] {180, 225, 255, 135};

	/**
	 * Mapper constructor.
	 * @param instance An instance of the UpdaterMain class.
	 */
	public Mapper(UpdaterMain instance) {
		plugin = instance;
		
		try {
			loadColors();
		} catch (Exception e) {
			plugin.getLogger().severe("An error occured whilst loading map color palette.");
			e.printStackTrace();
		}
		
		this.mapper = new Runnable() {
			
			@Override
			public void run() {
				if(plugin.updating) return;
				try {
					updateMap(false);
					System.gc();
				} catch (Exception e) {
					plugin.getLogger().severe("An error occured whilst updating the map.");
					e.printStackTrace();
					startMapper();
				}
			}
		};
		
		startMapper();
	};
	
	/**
	 * Start the mapper task.
	 */
	private void startMapper() {
		if(mapperTask != null) mapperTask.cancel();
		long delaytime = plugin.getConfig().getInt("render-update-time") * 20L;
		mapperTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, mapper, delaytime, delaytime);
	}
	
	/**
	 * Parse BlockMapColors.json and put colors into an array.
	 * @throws IOException Can throw an IOException if reading BlockMapColors.json goes wrong
	 * @throws ParseException Can throw a ParseException if JSON is invalid.
	 */
	private void loadColors() throws IOException, ParseException {
		mapColors = new HashMap<Material, Color>();
		colorIndexes = new ArrayList<Color>();

		JSONParser parser = new JSONParser();
		JSONArray ids = (JSONArray) parser.parse(new FileReader(plugin.getDataFolder() + "/BlockMapColors.json"));

		for (int i = 0; i < ids.size(); i++) {
			JSONObject id = (JSONObject) ids.get(i);
			JSONArray materials = (JSONArray) id.get("materials");
			JSONObject colorObj = (JSONObject) id.get("color");
			
			Color color = new Color(
					Integer.parseInt(colorObj.get("r").toString()), 
	                Integer.parseInt(colorObj.get("g").toString()), 
	                Integer.parseInt(colorObj.get("b").toString()), 
	                Integer.parseInt(colorObj.get("a").toString()));

			for (int m = 0; m < materials.size(); m++) {
				Material material = Material.getMaterial(materials.get(m).toString().toUpperCase());
				
				if (material == null) {
					throw new IllegalArgumentException(materials.get(m).toString().toUpperCase() + " is not a valid material.");
				}
				
				mapColors.put(material, color);
			}
			
			colorIndexes.add(color);
		}
	}
	
	/**
	 * Update the map with all edited chunks and edited minimaps.
	 * @param force Force an update.
	 * @throws IOException Can throw an IO exception when reading from chunkCache.json goes wrong.
	 * @throws ParseException Can throw a ParseException if JSON was invalid.
	 */
	public void updateMap(boolean force) throws IOException, ParseException {
		plugin.updating = true;
		
		ArrayList<int[]> current = plugin.watchdog.getUpdateBuffer();
		
		if(!current.isEmpty() || force) {
			if(plugin.broadcast) plugin.getServer().broadcastMessage(plugin.PREFIX + ChatColor.RED + "Updating map, this may be laggy.");
			
			int totalWorkload = current.size();
			double done = 0.0;
		
			plugin.getServer().getWorlds().get(0).setAutoSave(false);
			JSONArray data = new JSONArray();
	    	for (int[] block : current) {
				JSONObject b = new JSONObject();
				b.put("x", block[0]);
				b.put("z", block[1]);
				b.put("color", getBlockMapColor(block).getRGB());
				data.add(b);
				
				done++;
				if(plugin.debugLogging) plugin.getLogger().info("Updating: " + Math.round((done / totalWorkload) * 100) + "%");
			}
	    	
	    	plugin.sendDataToWebserver(data.toJSONString(), plugin.getConfig().getString("api-upload-url"));
	    	
			plugin.watchdog.clearChunkCache();
			
			plugin.getServer().getWorlds().get(0).setAutoSave(true);
			
			if(plugin.broadcast) plugin.getServer().broadcastMessage(plugin.PREFIX + ChatColor.GREEN + "Map updated.");
		}
		
		plugin.updating = false;
	}
	
	private Color getBlockMapColor(int[] block) {
		Block b = getHighestSolidAt(block[0], block[1], false, -1);
		
		if(colorIndexes.indexOf(mapColors.get(b.getType())) == 12) { //Water
			int depth = getHighestSolidYAt(block[0], block[1], true, b.getY());
			int d = b.getY() - depth;
			if(d > 4)                return getColorVariation(mapColors.get(b.getType()), 0);
			else if(d <= 4 && d > 2) return getColorVariation(mapColors.get(b.getType()), 1);
			else                     return getColorVariation(mapColors.get(b.getType()), 2);
		} else {
			int north = getHighestSolidYAt(block[0], block[1] - 1, false, -1);
			int d = north - b.getY();
			if(d > 0)       return getColorVariation(mapColors.get(b.getType()), 0);
			else if(d == 0) return getColorVariation(mapColors.get(b.getType()), 1);
			else            return getColorVariation(mapColors.get(b.getType()), 2);
		}
	}
		
	/**
	 * Get the highest solid excluding transparent blocks at the given coordinates.
	 * @param chunk The chunk that is being scanned.
	 * @param x The block X coordinate.
	 * @param z The block Z coordinate.
	 * @param start A start coordinate, from which to start checking. (Use 0 to disable).
	 * @param waterIsTransparent Count water as transparent blocks.
	 * @return the Y coordinate of the heightest non-transparent block.
	 */
	private int getHighestSolidYAt(int x, int z, boolean skipWater, int start) {
		Block b = plugin.getServer().getWorlds().get(0).getHighestBlockAt(x, z);
		int y = b.getY();
		if(start != -1) y = start;
		
		int colorIndex = colorIndexes.indexOf(mapColors.get(b.getType()));
		while(colorIndex == 0 || (skipWater && colorIndex == 12)) { //Skip transparent blocks
			y--;
			if(y < 1) {
				break;
			}
			
			colorIndex = colorIndexes.indexOf(mapColors.get(plugin.getServer().getWorlds().get(0).getBlockAt(x, y, z).getType()));
		}
		
		return y;
	}
	
	private Block getHighestSolidAt(int x, int z, boolean skipWater, int start) {
		return plugin.getServer().getWorlds().get(0).getBlockAt(x, getHighestSolidYAt(x, z, skipWater, start), z);
	}
	
	private Color getColorVariation(Color c, int var) {
		return new Color(
				Math.floorDiv(c.getRed() * colorVariations[var], 255),
				Math.floorDiv(c.getGreen() * colorVariations[var], 255),
				Math.floorDiv(c.getBlue() * colorVariations[var], 255),
				c.getAlpha());
	}
}