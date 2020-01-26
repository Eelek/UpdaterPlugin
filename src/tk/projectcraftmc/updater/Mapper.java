package tk.projectcraftmc.updater;

import java.awt.Color;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
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
		plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, mapper, delaytime, delaytime);
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
		
		int totalWorkload = apidata.size() + current.size();
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
			int parts = (int) Math.ceil(1.0d * sideLength / plugin.CHUNKSIZE);
			int x = Integer.parseInt(minimapObj.get("x").toString());
			int z = Integer.parseInt(minimapObj.get("z").toString());
			System.out.println("Size: "  + sideLength + " parts: " + parts);
			JSONArray data = new JSONArray();

			for(int zpart = 0; zpart < parts; zpart++) {
				for(int xpart = 0; xpart < parts; xpart++) {
					JSONArray map = generateMap(plugin.getServer().getWorlds().get(0), x + xpart * plugin.CHUNKSIZE, z + zpart * plugin.CHUNKSIZE, plugin.CHUNKSIZE);
					data.addAll(map);
				}
			}
			
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
	
	private JSONArray generateMap(World w, int startX, int startZ, int size) {
		JSONArray img = new JSONArray();
		
		int minX = Math.floorDiv(startX, size) * size;
		int minZ = Math.floorDiv(startZ, size) * size;
		
		ArrayList<ChunkSnapshot> chunks = new ArrayList<ChunkSnapshot>();
		int totalChunks = (int) Math.ceil((size) * size / (16 * 16)) + size / 16;
		
		for(int c = 0; c < totalChunks; c++) {
			chunks.add(w.getChunkAt(minX + c, minZ + c - size).getChunkSnapshot());
			System.out.println("Chunk loading: " + c + " / " + (totalChunks - 1));
		}
		
		for(int c = 0; c < size * size / (16 * 16); c++) {
			ChunkSnapshot chunk = chunks.get(c + size / 16);
			ChunkSnapshot north = chunks.get(c);
			
			for(int rz = 0; rz < 16; rz++) {
				for(int rx = 0; rx < 16; rx++) {
					int y = getHighestSolidAt(chunk, rx, rz);
					Material m = chunk.getBlockType(rx, y, rz);
					
					int northY = getHighestSolidAt(north, rx, rz);
					
					Color mColor = null;
					
					if(materialIndex.get(m) == 12) { //Water, color is depth dependant
						if(north.getBlockType(rz, northY, rz) == Material.WATER) { //In case of water, get depth
							northY = getLowestWaterBlock(north, rx, rz);
							if(y - northY > 3) { //Darker color (1st variant)
								mColor = colorIndex.get(materialIndex.get(m) * 4);
							} else if(y - northY  <= 3 && y - northY > 1) { //Normal color (2nd variant)
								mColor = colorIndex.get(materialIndex.get(m) * 4 + 1);
							} else { //Ligher color (3rd variant aka base color)
								mColor = colorIndex.get(materialIndex.get(m) * 4 + 2);
							}		
						} else { //Plants etc
							mColor = colorIndex.get(materialIndex.get(m) * 4);
						}
					} else { //Other blocks, color depends of the Y value of the block north of it.
						if(northY > y) { //Darker color (1st variant)
							mColor = colorIndex.get(materialIndex.get(m) * 4);
						} else if(northY == y) { //Normal color (2nd variant)
							mColor = colorIndex.get(materialIndex.get(m) * 4 + 1);
						} else { //Ligher color (3rd variant aka base color)
							mColor = colorIndex.get(materialIndex.get(m) * 4 + 2);
						}	
					}
					
					img.add(mColor.getRed());					
					img.add(mColor.getGreen());
					img.add(mColor.getBlue());
				}
			}
		}
		
		return img;
	}
	
	/*
	private JSONArray generateMap(World w, int startX, int startZ, int size) {
		JSONArray img = new JSONArray();

		int minX = Math.floorDiv(startX, size) * size;
		int minZ = Math.floorDiv(startZ, size) * size;
		
		ChunkSnapshot chunk = w.getChunkAt(minX, minZ).getChunkSnapshot();
		ChunkSnapshot northChunk = null;
		if(startZ - minZ == 0) {
			northChunk = w.getChunkAt(minX, minZ - 1).getChunkSnapshot();
		} else {
			northChunk = chunk;
		}
		
		int zChunkCounter = 0;

		for (int z = 0; z < size; z++) {
			for (int x = 0; x < size; x++) {
				int chunkX = x + minX - Math.floorDiv(x + minX, 16) * 16; //chunk.getX() just returns 0. :/
				int chunkZ = z + minZ - Math.floorDiv(z + minZ, 16) * 16;
				
				if(zChunkCounter == 0 && z != 0) {
					northChunk = chunk;
				}
				
				if(zChunkCounter > 15) {
					northChunk = chunk;
					chunk = w.getChunkAt(x + minX, z + minZ).getChunkSnapshot();
					zChunkCounter = 0;
				}
				
				if(xChunkCounter > 15 && zChunkCounter < 16) {
					chunk = w.getChunkAt(x + minX, z + minZ).getChunkSnapshot();
					xChunkCounter = 0;
				}
				
				int y = getHighestSolidAt(chunk, chunkX, chunkZ);
				Material m = chunk.getBlockType(chunkX, y, chunkZ);
				
				int northChunkX = x + minX - Math.floorDiv(x + minX, 16) * 16; //chunk.getX() just returns 0. :/
				int northChunkZ = z + minZ - 1 - Math.floorDiv(z + minZ - 1, 16) * 16;
				int northY = getHighestSolidAt(northChunk, northChunkX, northChunkZ);
				Material northM = northChunk.getBlockType(northChunkX, northY, northChunkZ);
				
				Color mColor = null;
				
				try {
					if(materialIndex.get(m) == 12) { //Water, color is depth dependant
						if(northM == Material.WATER) {
							northY = getLowestWaterBlock(northChunk, northChunkX, northChunkZ);
							if(y - northY > 3) { //Darker color (1st variant)
								mColor = colorIndex.get(materialIndex.get(m) * 4);
							} else if(y - northY  <= 3 && y - northY > 1) { //Normal color (2nd variant)
								mColor = colorIndex.get(materialIndex.get(m) * 4 + 1);
							} else { //Ligher color (3rd variant aka base color)
								mColor = colorIndex.get(materialIndex.get(m) * 4 + 2);
							}		
						} else {
							mColor = colorIndex.get(materialIndex.get(m) * 4);
						}
					} else { //Other blocks, color depends of the Y value of the block north of it.
						if(northY > y) { //Darker color (1st variant)
							mColor = colorIndex.get(materialIndex.get(m) * 4);
						} else if(northY == y) { //Normal color (2nd variant)
							mColor = colorIndex.get(materialIndex.get(m) * 4 + 1);
						} else { //Ligher color (3rd variant aka base color)
							mColor = colorIndex.get(materialIndex.get(m) * 4 + 2);
						}	
					}
					
					img.add(mColor.getRed());					
					img.add(mColor.getGreen());
					img.add(mColor.getBlue());
				} catch(NullPointerException e) {
					plugin.getLogger().warning("Unknown Material: " + m + "\n");
					e.printStackTrace();
				}
				xChunkCounter++;
			}
			zChunkCounter++;
		}

		return img;
	}
	*/
	
	private JSONArray compressMap(JSONArray img, int compression, int side) {
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
	
	private int getHighestSolidAt(ChunkSnapshot chunk, int x, int z) {
		int y = chunk.getHighestBlockYAt(x, z);
		
		while(materialIndex.get(chunk.getBlockType(x, y, z)) == 0) { //Skip transparent blocks
			y--;
		}
		
		return y;
	}
	
	private int getLowestWaterBlock(ChunkSnapshot chunk, int x, int z) {
		int y = chunk.getHighestBlockYAt(x, z);
		
		while(materialIndex.get(chunk.getBlockType(x, y, z)) == 0 || materialIndex.get(chunk.getBlockType(x, y, z)) == 12) { //Water blocks
			y--;
		}
		
		return y + 1;
	}
}
