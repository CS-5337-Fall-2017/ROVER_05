package swarmBots;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.Type;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import MapSupport.Coord;
import MapSupport.MapTile;
import MapSupport.ScanMap;
import common.Rover;
import communicationInterface.Communication;
import communicationInterface.RoverDetail;
import communicationInterface.ScienceDetail;
import enums.RoverConfiguration;
import enums.RoverDriveType;
import enums.RoverMode;
import enums.RoverToolType;
import enums.Terrain;
import rover_logic.Astar;
import swarmBots.ROVER_03.Direction;
import swarmBots.ROVER_03.MoveTargetLocation;

/*
 * The seed that this program is built on is a chat program example found here:
 * http://cs.lmu.edu/~ray/notes/javanetexamples/ Many thanks to the authors for
 * publishing their code examples
 */

/**
 * 
 * @author rkjc
 * 
 * ROVER_05 is intended to be a basic template to start building your rover on
 * Start by refactoring the class name to match your rovers name.
 * Then do a find and replace to change all the other instances of the 
 * name "ROVER_05" to match your rovers name.
 * 
 * The behavior of this robot is a simple travel till it bumps into something,
 * sidestep for a short distance, and reverse direction,
 * repeat.
 * 
 * This is a terrible behavior algorithm and should be immediately changed.
 *
 */

public class ROVER_05 extends Rover {

	/**
	 * Runs the client
	 */
	public static void main(String[] args) throws Exception {
		ROVER_05 client;
    	// if a command line argument is present it is used
		// as the IP address for connection to RoverControlProcessor instead of localhost 
		
		if(!(args.length == 0)){
			client = new ROVER_05(args[0]);
		} else {
			client = new ROVER_05();
		}
		
		client.run();
	}

	public ROVER_05() {
		// constructor
		System.out.println("ROVER_05 rover object constructed");
		rovername = "ROVER_05";
	}
	
	public ROVER_05(String serverAddress) {
		// constructor
		System.out.println("ROVER_05 rover object constructed");
		rovername = "ROVER_05";
		SERVER_ADDRESS = serverAddress;
	}

	static enum Direction {
		NORTH, SOUTH, EAST, WEST;

		static Map<Character, Direction> map = new HashMap<Character, Direction>() {
			private static final long serialVersionUID = 1L;

			{
				put('N', NORTH);
				put('S', SOUTH);
				put('E', EAST);
				put('W', WEST);
			}
		};

		static Direction get(char c) {
			return map.get(c);
		}
	}

	static class MoveTargetLocation {
		Coord targetCoord;
		Direction d;
	}

	static Map<Coord, Integer> coordVisitCountMap = new HashMap<Coord, Integer>() {
		private static final long serialVersionUID = 1L;

		@Override
		public Integer get(Object key) {
			if (!containsKey(key)) {
				super.put((Coord) key, new Integer(0));
			}
			return super.get(key);
		}
	};

	Coord maxCoord = new Coord(0, 0);

	

	/**
	 * 
	 * The Rover Main instantiates and runs the rover as a runnable thread
	 * 
	 */
	private void run() throws IOException, InterruptedException {
		// Make a socket for connection to the RoverControlProcessor
		Socket socket = null;
		try {
			socket = new Socket(SERVER_ADDRESS, PORT_ADDRESS);

			// sets up the connections for sending and receiving text from the RCP
			receiveFrom_RCP = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			sendTo_RCP = new PrintWriter(socket.getOutputStream(), true);
			
			// Need to allow time for the connection to the server to be established
			sleepTime = 300;
			
			/*
			 * After the rover has requested a connection from the RCP
			 * this loop waits for a response. The first thing the RCP requests is the rover's name
			 * once that has been provided, the connection has been established and the program continues 
			 */
			while (true) {
				String line = receiveFrom_RCP.readLine();
				if (line.startsWith("SUBMITNAME")) {
					//This sets the name of this instance of a swarmBot for identifying the thread to the server
					sendTo_RCP.println(rovername); 
					break;
				}
			}
	
	
			
			/**
			 *  ### Setting up variables to be used in the Rover control loop ###
			 *  add more as needed
			 */
			int stepCount = 0;	
			String line = "";	
			boolean goingSouth = false;
			boolean goingWest = false;
			boolean stuck = false; // just means it did not change locations between requests,
									// could be velocity limit or obstruction etc.
			boolean blocked = false;
			
			Astar aStar = new Astar();
			
			RoverMode roverMode = RoverMode.EXPLORE;
			//ScienceDetail scienceDetail = null;
	
			// might or might not have a use for this
//			String[] cardinals = new String[4];
//			cardinals[0] = "N";
//			cardinals[1] = "E";
//			cardinals[2] = "S";
//			cardinals[3] = "W";	
//			String currentDir = cardinals[0];		
			

			/**
			 *  ### Retrieve static values from RoverControlProcessor (RCP) ###
			 *  These are called from outside the main Rover Process Loop
			 *  because they only need to be called once
			 */		
			
			// **** get equipment listing ****			
			equipment = getEquipment();
			System.out.println(rovername + " equipment list results " + equipment + "\n");
			
			
			// **** Request START_LOC Location from SwarmServer **** this might be dropped as it should be (0, 0)
			startLocation = getStartLocation();
			System.out.println(rovername + " START_LOC " + startLocation);
			
			
			// **** Request TARGET_LOC Location from SwarmServer ****
			targetLocation = getTargetLocation();
			System.out.println(rovername + " TARGET_LOC " + targetLocation);
			
			
	        // **** Define the communication parameters and open a connection to the 
			// SwarmCommunicationServer restful service through the Communication.java class interface
	        String url = "http://localhost:2681/api"; // <----------------------  this will have to be changed if multiple servers are needed
	        String corp_secret = "gz5YhL70a2"; // not currently used - for future implementation
	
	        Communication com = new Communication(url, rovername, corp_secret);
	
	       // RoverMode roverMode = RoverMode.EXPLORE;
			ScienceDetail scienceDetail = null;
			/**
			 *  ####  Rover controller process loop  ####
			 *  This is where all of the rover behavior code will go
			 *  
			 */
			while (true) {                     //<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<
		
				// **** Request Rover Location from RCP ****
				currentLoc = getCurrentLocation();
				System.out.println(rovername + " currentLoc at start: " + currentLoc);
				
				// after getting location set previous equal current to be able
				// to check for stuckness and blocked later
				previousLoc = currentLoc;		
				
				

				// ***** do a SCAN *****
				// gets the scanMap from the server based on the Rover current location
				scanMap = doScan(); 
				// prints the scanMap to the Console output for debug purposes
				//scanMap.debugPrintMap();
				
				
				MapTile[][] scanMapTiles = scanMap.getScanMap();
				int mapTileCenter = (scanMap.getEdgeSize() - 1) / 2;
				Coord currentLocInMapTile = new Coord(mapTileCenter, mapTileCenter);

				int maxX = currentLoc.xpos + mapTileCenter;
				int maxY = currentLoc.ypos + mapTileCenter;
				if (maxCoord.xpos < maxX && maxCoord.ypos < maxY) {
					maxCoord = new Coord(maxX, maxY);
				} else if (maxCoord.xpos < maxX) {
					maxCoord = new Coord(maxX, maxCoord.ypos);
				} else if (maxCoord.ypos < maxY) {
					maxCoord = new Coord(maxCoord.xpos, maxY);
				}

				// ***** MOVING *****
				MoveTargetLocation moveTargetLocation = null;

				
			 
					moveTargetLocation = chooseMoveTargetLocation(scanMapTiles, currentLocInMapTile, currentLoc,
							mapTileCenter);

					System.out.println("*****> In explore mode in the direction " + moveTargetLocation.d);
				

				if (moveTargetLocation != null && moveTargetLocation.d != null) {
					switch (moveTargetLocation.d) {
					case NORTH:
						moveNorth();
						break;
					case EAST:
						moveEast();
						break;
					case SOUTH:
						moveSouth();
						break;
					case WEST:
						moveWest();
						break;
					default:
						roverMode = RoverMode.EXPLORE;
					}

					if (!previousLoc.equals(getCurrentLocation())) {
						coordVisitCountMap.put(moveTargetLocation.targetCoord,
								coordVisitCountMap.get(moveTargetLocation.targetCoord) + 1);
					}
				}

				try {
					sendRoverDetail(roverMode);
					postScanMapTiles();
				} catch (Exception e) {
					System.err.println("Post current map to communication server failed. Cause: "
							+ e.getClass().getName() + ": " + e.getMessage());
				}

				// this is the Rovers HeartBeat, it regulates how fast the Rover
				// cycles through the control loop
				Thread.sleep(sleepTime);

				System.out.println("ROVER_05 ------------ bottom process control --------------");
			} // END of Rover control While(true) loop

			// This catch block closes the open socket connection to the server
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			if (socket != null) {
				try {
					socket.close();
				} catch (IOException e) {
					System.out.println("ROVER_05 problem closing socket");
				}
			}
		}

	} // END of Rover run thread

	private Coord getCoordNorthOf(Coord c) {

		return new Coord(c.xpos, c.ypos - 1);
	}

	private Coord getCoordEastOf(Coord c) {

		return new Coord(c.xpos + 1, c.ypos);
	}

	private Coord getCoordSouthOf(Coord c) {

		return new Coord(c.xpos, c.ypos + 1);
	}

	private Coord getCoordWestOf(Coord c) {

		return new Coord(c.xpos - 1, c.ypos);
	}

	private boolean isBlocked(MapTile[][] mapTiles, Coord c) {

		return mapTiles[c.xpos][c.ypos].getHasRover() || mapTiles[c.xpos][c.ypos].getTerrain() == Terrain.ROCK
				|| mapTiles[c.xpos][c.ypos].getTerrain() == Terrain.NONE;
	}

	private MoveTargetLocation chooseMoveTargetLocation(MapTile[][] scanMapTiles, Coord currentLocInMapTile,
			Coord currentLoc, int mapTileCenter) {

		Coord northCoordInMapTile = getCoordNorthOf(currentLocInMapTile);
		Coord eastCoordInMapTile = getCoordEastOf(currentLocInMapTile);
		Coord southCoordInMapTile = getCoordSouthOf(currentLocInMapTile);
		Coord westCoordInMapTile = getCoordWestOf(currentLocInMapTile);

		Coord northCoord = getCoordNorthOf(currentLoc);
		Coord eastCoord = getCoordEastOf(currentLoc);
		Coord southCoord = getCoordSouthOf(currentLoc);
		Coord westCoord = getCoordWestOf(currentLoc);

		int min = Integer.MAX_VALUE;

		MoveTargetLocation moveTargetLocation = new MoveTargetLocation();

		Stack<Direction> favoredDirStack = getFavoredDirStack(currentLoc, mapTileCenter);

		while (!favoredDirStack.isEmpty()) {
			Direction d = favoredDirStack.pop();
			switch (d) {
			case NORTH:
				if (!isBlocked(scanMapTiles, northCoordInMapTile) && coordVisitCountMap.get(northCoord) < min) {
					min = coordVisitCountMap.get(northCoord);
					moveTargetLocation.targetCoord = northCoord;
					moveTargetLocation.d = Direction.NORTH;
				}
				break;
			case EAST:
				if (!isBlocked(scanMapTiles, eastCoordInMapTile) && coordVisitCountMap.get(eastCoord) < min) {
					min = coordVisitCountMap.get(eastCoord);
					moveTargetLocation.targetCoord = eastCoord;
					moveTargetLocation.d = Direction.EAST;
				}
				break;
			case SOUTH:
				if (!isBlocked(scanMapTiles, southCoordInMapTile) && coordVisitCountMap.get(southCoord) < min) {
					min = coordVisitCountMap.get(southCoord);
					moveTargetLocation.targetCoord = southCoord;
					moveTargetLocation.d = Direction.SOUTH;
				}
				break;
			case WEST:
				if (!isBlocked(scanMapTiles, westCoordInMapTile) && coordVisitCountMap.get(westCoord) < min) {
					min = coordVisitCountMap.get(westCoord);
					moveTargetLocation.targetCoord = westCoord;
					moveTargetLocation.d = Direction.WEST;
				}
			}
		}
		printMoveTargetLocation(moveTargetLocation);
		return moveTargetLocation;
	}

	private Stack<Direction> getFavoredDirStack(Coord currentLoc, int mapTileCenter) {

		int northUnvisitedCount = 0, eastUnvisitedCount = 0, southUnvisitedCount = 0, westUnvisitedCount = 0;
		for (int x = 0; x < currentLoc.xpos; x++) {
			if (coordVisitCountMap.get(new Coord(x, currentLoc.ypos)) == 0) {
				westUnvisitedCount++;
			}
		}
		for (int x = currentLoc.xpos; x < maxCoord.xpos; x++) {
			if (coordVisitCountMap.get(new Coord(x, currentLoc.ypos)) == 0) {
				eastUnvisitedCount++;
			}
		}
		for (int y = 0; y < currentLoc.ypos; y++) {
			if (coordVisitCountMap.get(new Coord(currentLoc.xpos, y)) == 0) {
				northUnvisitedCount++;
			}
		}
		for (int y = currentLoc.ypos; y < maxCoord.ypos; y++) {
			if (coordVisitCountMap.get(new Coord(currentLoc.xpos, y)) == 0) {
				southUnvisitedCount++;
			}
		}
		List<Integer> countList = Arrays.asList(northUnvisitedCount, eastUnvisitedCount, southUnvisitedCount,
				westUnvisitedCount);
		Collections.sort(countList);

		Stack<Direction> directionStack = new Stack<>();

		for (Integer count : countList) {
			if (count == northUnvisitedCount && !directionStack.contains(Direction.NORTH)) {
				directionStack.push(Direction.NORTH);
			}
			if (count == eastUnvisitedCount && !directionStack.contains(Direction.EAST)) {
				directionStack.push(Direction.EAST);
			}
			if (count == southUnvisitedCount && !directionStack.contains(Direction.SOUTH)) {
				directionStack.push(Direction.SOUTH);
			}
			if (count == westUnvisitedCount && !directionStack.contains(Direction.WEST)) {
				directionStack.push(Direction.WEST);
			}
		}
		System.out.println("counts = North(" + northUnvisitedCount + ") East(" + eastUnvisitedCount + ") South("
				+ southUnvisitedCount + ") West(" + westUnvisitedCount + ")");
		// System.out.println("countList = " + countList);
		System.out.println("favoredDirStack = " + directionStack);
		// System.out.println("coordVisitCountMap = " + coordVisitCountMap);

		return directionStack;
	}

	private void printMoveTargetLocation(MoveTargetLocation moveTargetLocation) {

		System.out.println("MoveTargetLocation.x = " + moveTargetLocation.targetCoord.xpos);
		System.out.println("MoveTargetLocation.y = " + moveTargetLocation.targetCoord.ypos);
		System.out.println("MoveTargetLocation.d = " + moveTargetLocation.d);
	}

	

}