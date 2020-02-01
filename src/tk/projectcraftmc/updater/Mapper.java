package tk.projectcraftmc.updater;

import java.awt.Color;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

@SuppressWarnings("unchecked")
public class Mapper {
	
	private UpdaterMain plugin;
	private Runnable mapper;
	
	private ArrayList<Color> colorIndex;
	private HashMap<Material, Integer> materialIndex;

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
					updateMap();
				} catch(Exception e) {
					plugin.getLogger().severe("An error occured whilst updating the map.");
					e.printStackTrace();
				}
			}
		};
		
		long delaytime = plugin.getConfig().getInt("render-update-time") * 20L;
		plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, mapper, 200, delaytime);
	};
	
	private void loadColors() throws IOException, ParseException {
		FileReader fileReader = new FileReader(plugin.getDataFolder() + "/BlockMapColors.json");
		
		StringBuilder content = new StringBuilder();
		
		int c;
		while((c = fileReader.read()) != -1) {
			content.append((char) c);
		}
			
		fileReader.close();
			
		colorIndex = new ArrayList<Color>();
		materialIndex = new HashMap<Material, Integer>();

		JSONParser parser = new JSONParser();
		Object object = parser.parse(content.toString());
		JSONArray ids = (JSONArray) object;

		for (int i = 0; i < ids.size(); i++) {
			JSONObject id = (JSONObject) ids.get(i);
			JSONArray materials = (JSONArray) id.get("materials");
			JSONObject colorObj = (JSONObject) id.get("color");

			for (int m = 0; m < materials.size(); m++) {
				Material material = Material.getMaterial(materials.get(m).toString().toUpperCase());
				
				if (material == null) {
					throw new IllegalArgumentException(materials.get(m).toString().toUpperCase() + " is not a valid material.");
				}
					
				materialIndex.put(material, i);				
			}
			
			Color color = new Color(
					Integer.parseInt(colorObj.get("r").toString()), 
	                Integer.parseInt(colorObj.get("g").toString()), 
	                Integer.parseInt(colorObj.get("b").toString()), 
	                Integer.parseInt(colorObj.get("a").toString()));
			
			colorIndex.add(new Color(
					Math.floorDiv(color.getRed() * 180, 255),
					Math.floorDiv(color.getGreen() * 180, 255),
					Math.floorDiv(color.getBlue() * 180, 255),
					color.getAlpha()));
			
			colorIndex.add(new Color(
					Math.floorDiv(color.getRed() * 220, 255),
					Math.floorDiv(color.getGreen() * 220, 255),
					Math.floorDiv(color.getBlue() * 220, 255),
					color.getAlpha()));
			
			colorIndex.add(color);
			
			colorIndex.add(new Color(
					Math.floorDiv(color.getRed() * 135, 255),
					Math.floorDiv(color.getGreen() * 135, 255),
					Math.floorDiv(color.getBlue() * 135, 255),
					color.getAlpha()));
		}
	}
	
	public void updateMap() throws IOException, ParseException {
		plugin.getServer().broadcastMessage("Updating map, this may be laggy.");
		
		plugin.updating = true;
		
		JSONParser parser = new JSONParser();
		
		ArrayList<SuperChunk> current = plugin.watchdog.getEditedChunks();
		JSONObject apidata = (JSONObject) parser.parse(plugin.getDataFromWebserver(plugin.getConfig().getString("api-fetch-url")));
		JSONArray minimaps = (JSONArray) apidata.get("miniMapList");
		
		int totalWorkload = minimaps.size() + current.size();
		double done = 0.0;
		
		if(!current.isEmpty()) {
	    	for (SuperChunk c : current) {
				JSONArray data = compressMap(generateMap(c.getWorld(), c.getX(), c.getZ(), plugin.CHUNKSIZE), plugin.COMPRESSION, plugin.CHUNKSIZE);	    		
				
				JSONObject metaData = new JSONObject();
				metaData.put("x", c.getX());
				metaData.put("z", c.getZ());
				metaData.put("size", plugin.CHUNKSIZE);
				metaData.put("world", c.getWorld().getEnvironment().toString().toLowerCase());
				metaData.put("isMiniMap", false);
				
				JSONObject complete = new JSONObject();
				complete.put("data", data);
				complete.put("metaData", metaData);
				
				plugin.sendDataToWebserver(complete.toJSONString(), plugin.getConfig().getString("api-upload-url"));
				done++;
				plugin.getLogger().info("Updating: " + done / totalWorkload * 100 + "%"); 
			}
		}
		
		plugin.watchdog.clearChunkCache();
		
		for(int m = 0; m < minimaps.size(); m++) {
			JSONObject minimapObj = (JSONObject) minimaps.get(m);
			int sideLength = Integer.parseInt(minimapObj.get("size").toString());
			int x = Integer.parseInt(minimapObj.get("x").toString());
			int z = Integer.parseInt(minimapObj.get("z").toString());
			JSONArray data = new JSONArray();

			data.addAll(generateMap(plugin.getServer().getWorlds().get(0), x, z, sideLength));
			
			JSONObject metaData = new JSONObject();
			metaData.put("x", x);
			metaData.put("z", z);
			metaData.put("size", sideLength);
			metaData.put("world", "normal");
			metaData.put("isMiniMap", true);
			
			JSONObject complete = new JSONObject();
			complete.put("data", data);
			complete.put("metaData", metaData);
			
			plugin.sendDataToWebserver(complete.toJSONString(), plugin.getConfig().getString("api-upload-url"));
			done++;
			plugin.getLogger().info("Updating: " + Math.round((done / totalWorkload) * 100) + "%");
		}
		
		plugin.getServer().broadcastMessage("Map updated.");
		plugin.updating = false;
		System.gc();
	}
	
	private ArrayList<Integer> generateMap(World w, int startX, int startZ, int size) {
		ArrayList<Integer> img = new ArrayList<Integer>(Collections.nCopies(size * size * 3, null));
		
		int minX = Math.floorDiv(startX, 16) * 16;
		int minZ = Math.floorDiv(startZ, 16) * 16;
		
		ArrayList<ChunkSnapshot> cache = new ArrayList<ChunkSnapshot>();
		
		int totalChunks = size * size / (16 * 16);
		int chunkSides = size / 16;
		
		for(int nz = -1; nz < chunkSides; nz++) {
			for(int nx = 0; nx < chunkSides; nx++) {
				int xOffset = minX + nx * 16;
				int zOffset = minZ + nz * 16;
				cache.add(w.getChunkAt(xOffset, zOffset).getChunkSnapshot());
			}
		}
		
		for(int c = 0; c < totalChunks; c++) {
			ChunkSnapshot chunk = cache.get(chunkSides);
			ChunkSnapshot north = null;
			
			for(int rz = 0; rz < 16; rz++) {
				int northOffset = rz - 1;
				if(rz == 0) {
					northOffset = 15;
					north = cache.get(0);
				} else if (rz == 1) {
					north = chunk;
					cache.remove(0);
				}
				
				for(int rx = 0; rx < 16; rx++) {
					int y = getHighestSolidAt(chunk, rx, rz, -1, false);
					Material m = chunk.getBlockType(rx, y, rz);
					int northY = getHighestSolidAt(north, rx, northOffset, -1, false);
					
					Color mColor = getBlockColor(materialIndex.get(m), y - northY);
					
					int pixelOffset = 3 * (c * 16 * 16 + rz * 16 + rx);
					img.set(pixelOffset    , mColor.getRed());
					img.set(pixelOffset + 1, mColor.getGreen());
					img.set(pixelOffset + 2, mColor.getBlue());
				}
			}
			System.out.println("Chunk mapped " + chunk.getX() + " " + chunk.getZ());
		}
		
		return img;
	}

	private Color getBlockColor(int mIndex, int dY) {
        if (mIndex == 12) {
			if (dY > 3) 			return colorIndex.get(mIndex * 4);
			if (dY > 1 && dY <= 3)	return colorIndex.get(mIndex * 4 + 1);
									return colorIndex.get(mIndex * 4 + 2);
		}

		if (dY > 0) 	return colorIndex.get(mIndex * 4);
		if (dY == 0) 	return colorIndex.get(mIndex * 4 + 1);
						return colorIndex.get(mIndex * 4 + 2);
	}
	
	private JSONArray compressMap(ArrayList<Integer> img, int compression, int side) {
        JSONArray newImg = new JSONArray();
        int newPixelCount = (img.size() / 3) / (compression * compression);
        int newWidth = (int) Math.floor(Math.sqrt(newPixelCount));

        for(int pixel = 0; pixel < newPixelCount; pixel++) {
            int red = 0;
            int green = 0;
            int blue = 0;
            
            for (int rz = 0; rz < compression; rz++) {
                for (int rx = 0; rx < compression; rx++) {
                    int curPixelY = Math.floorDiv(pixel, newWidth);
                    int curPixelX = pixel - curPixelY * newWidth;
                    int yOffset   = curPixelY * compression * side;
                    int curIndex  = 3 * (rx + rz * side + curPixelX * compression + yOffset);

                    red   += Integer.parseInt(img.get(curIndex).toString());
                    green += Integer.parseInt(img.get(curIndex + 1).toString());
                    blue  += Integer.parseInt(img.get(curIndex + 2).toString());
                }
            }
            
            newImg.add(Math.floorDiv(red, compression * compression));
            newImg.add(Math.floorDiv(green, compression * compression));
            newImg.add(Math.floorDiv(blue, compression * compression));
        }
        
        return newImg;
    }
	
	/**
	 * Get the highest solid excluding transparent blocks at the given coordinates.
	 * @param chunk The chunk that is being scanned.
	 * @param x The block X coordinate.
	 * @param z The block Z coordinate.
	 * @param start A start coordinate, from which to start checking.
	 * @param waterIsTransparent Count water as transparent blocks.
	 * @return the Y coordinate of the heightest non-transparent block.
	 */
	private int getHighestSolidAt(ChunkSnapshot chunk, int x, int z, int start, boolean waterIsTransparent) {
		int y = 255;
		if(start == -1) {
			y = chunk.getHighestBlockYAt(x, z);
		} else {
			y = start;
		}
		
		while(materialIndex.get(chunk.getBlockType(x, y, z)) == 0 || (materialIndex.get(chunk.getBlockType(x, y, z)) == 12 && waterIsTransparent) ) { //Skip transparent blocks
			y--;
		}
		
		return y;
	}
}
