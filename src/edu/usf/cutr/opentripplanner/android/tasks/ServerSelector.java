/*
 * Copyright 2011 Marcy Gordon
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package edu.usf.cutr.opentripplanner.android.tasks;

import static edu.usf.cutr.opentripplanner.android.OTPApp.PREFERENCE_KEY_AUTO_DETECT_SERVER;
import static edu.usf.cutr.opentripplanner.android.OTPApp.PREFERENCE_KEY_CUSTOM_SERVER_URL;
import static edu.usf.cutr.opentripplanner.android.OTPApp.PREFERENCE_KEY_CUSTOM_SERVER_URL_IS_VALID;
import static edu.usf.cutr.opentripplanner.android.OTPApp.PREFERENCE_KEY_SELECTED_CUSTOM_SERVER;
import static edu.usf.cutr.opentripplanner.android.OTPApp.PREFERENCE_KEY_SELECTED_SERVER;

import java.io.IOException;
import java.io.StringReader;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.DefaultHttpClient;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.AsyncTask;
import android.preference.PreferenceManager;
import android.util.Log;
import android.webkit.URLUtil;
import android.widget.EditText;
import android.widget.Toast;
import au.com.bytecode.opencsv.CSVReader;

import com.google.android.gms.maps.model.LatLng;

import de.mastacode.http.Http;
import edu.usf.cutr.opentripplanner.android.OTPApp;
import edu.usf.cutr.opentripplanner.android.R;
import edu.usf.cutr.opentripplanner.android.listeners.ServerCheckerCompleteListener;
import edu.usf.cutr.opentripplanner.android.listeners.ServerSelectorCompleteListener;
import edu.usf.cutr.opentripplanner.android.model.Server;
import edu.usf.cutr.opentripplanner.android.sqlite.ServersDataSource;
import edu.usf.cutr.opentripplanner.android.util.LocationUtil;

/**
 * A task that retrieves the list of OTP servers from the Google Docs directory,
 * and if specified, automatically chooses the server based on the geographic bounds
 * and user current location
 * 
 * @author Marcy Gordon
 * @author Khoa Tran
 */

public class ServerSelector extends AsyncTask<LatLng, Integer, Long> implements ServerCheckerCompleteListener{
	private Server selectedServer;
	private static final String TAG = "OTP";
	private ProgressDialog progressDialog;
	private WeakReference<Activity> activity;
	private Context context;
	private static List<Server> knownServers = new ArrayList<Server>();
	private boolean mustRefreshList = false;
	private boolean isAutoDetectEnabled = true;
	private ServerSelectorCompleteListener callback;
	private boolean selectedCustomServer;
	private boolean showDialog;

    public ServersDataSource dataSource = null;
    
    /**
     * Constructs a new ServerSelector
     * @param context
     * @param dataSource
     * @param callback
     * @param mustRefreshList true if we should download a new list of servers from the Google Doc, false if we should use cached list of servers
     * @param isAutoDetectEnabled true if we should automatically compare the user's current location to the bounding boxes of OTP servers, false if we should prompt the user to pick the OTP server manually
     * @param showDialog true if a progress dialog is requested
     */
	public ServerSelector(WeakReference<Activity> activity, Context context, ServersDataSource dataSource, ServerSelectorCompleteListener callback, boolean mustRefreshList, boolean showDialog) {
		this.activity = activity;
		this.context = context;
		this.dataSource = dataSource;
		this.callback = callback;
		this.mustRefreshList = mustRefreshList;
		this.showDialog = showDialog;
		if ((activity.get() != null) && showDialog){
			progressDialog = new ProgressDialog(activity.get());
		}
	}

	protected void onPreExecute() {
		if ((activity.get() != null) && showDialog){
			progressDialog.setIndeterminate(true);
	        progressDialog.setCancelable(true);
			progressDialog = ProgressDialog.show(activity.get(), "",
					context.getResources().getString(R.string.server_selector_progress), true);
		}
        
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        
		isAutoDetectEnabled = prefs.getBoolean(OTPApp.PREFERENCE_KEY_AUTO_DETECT_SERVER,
				true);
	}


	protected Long doInBackground(LatLng... latLng) {		
		LatLng currentLocation = latLng[0];
		
		List<Server> serverList = null;
		
		// If not forced to refresh list
		if(!mustRefreshList){
			// Check if servers are stored in SQLite?
			Log.v(TAG, "Attempt retrieving servers from sqlite");
			serverList = getServersFromSQLite();
		}
		
		// If forced to refresh list OR
		// If severs are not stored, download list from the Google Spreadsheet and Insert to database
		if(serverList == null || serverList.isEmpty() || mustRefreshList){
			Log.v(TAG, "No data from sqlite. Attempt retrieving servers from google spreadsheet");
			serverList = downloadServerList(context.getResources().getString(R.string.servers_spreadsheet_url));

			// Insert new list to database
			if(serverList!=null && !serverList.isEmpty()){
				insertServerListToDatabase(serverList);
				serverList = getServersFromSQLite();
			}
		
			// If still null
			if (serverList == null || serverList.isEmpty()) {
				return null;
			}
		}
		
		knownServers.clear();
		knownServers.addAll(serverList);
		
		//If we're autodetecting a server, get the location find the optimal server
		if(isAutoDetectEnabled && (currentLocation != null)){
			selectedServer = findOptimalSever(currentLocation);
		}
		
		long totalSize = serverList.size();
		return totalSize;
	}

	private List<Server> getServersFromSQLite(){
		List<Server> servers = new ArrayList<Server>();
		
		dataSource.open();
		List<Server> values = dataSource.getMostRecentServers();
		String shown = "";
		for(int i=0; i<values.size(); i++){
			Server s = values.get(i);
			shown += s.getRegion() + s.getDate().toString()+"\n";
			servers.add(new Server(s));
		}
		Log.v(TAG, shown);
		dataSource.close();
		
		dataSource.open();
		Calendar someDaysBefore = Calendar.getInstance();
		someDaysBefore.add(Calendar.DAY_OF_MONTH, - OTPApp.EXPIRATION_DAYS_FOR_SERVER_LIST);
		Long serversUpdateDate = dataSource.getMostRecentDate();
		if ((serversUpdateDate != null) && (someDaysBefore.getTime().getTime() > serversUpdateDate)){
			servers = null;
		}
		dataSource.close();
//		Toast.makeText(activity.getApplicationContext(), shown, Toast.LENGTH_SHORT).show();
		
		return servers;
	}

	/**
	 * Downloads the list of OTP servers from the Google Doc directory
	 * @param url URL address of the Google Doc
	 * @return Server a list of OTP servers contained in the Google Doc
	 */
	private List<Server> downloadServerList(String url){
		List<Server> serverList = new ArrayList<Server>();

		HttpClient client = new DefaultHttpClient();
		String result = "";
		try {
			result = Http.get(url).use(client).charset(OTPApp.URL_ENCODING).followRedirects(true).asString();
			Log.d(TAG, "Spreadsheet: " + result);
		} catch (IOException e) {
			Log.e(TAG, "Unable to download spreadsheet with server list: " + e.getMessage());
			return null;
		}

		CSVReader reader = new CSVReader(new StringReader(result));
		try {
			Long currentTime = Calendar.getInstance().getTime().getTime();
			
			List<String[]> entries = reader.readAll();
			for (String[] e : entries) {
				if(e[0].equalsIgnoreCase("Region")) {
					continue; //Ignore the first line of the file
				}
				
				
				Server s = new Server(currentTime, e[0], e[1], e[2], e[3], e[4], e[5], e[6], e[7]);
				serverList.add(s);
			}
		} catch (IOException e) {
			Log.e(TAG, "Problem reading CSV server file: " + e.getMessage());
			
			if(reader != null){
				try {
					reader.close();
				} catch (IOException e2) {
					Log.e(TAG, "Error closing CSVReader file: " + e2);
				}
			}
			
			return null;
		}finally{
			if(reader != null){
				try {
					reader.close();
				} catch (IOException e) {
					Log.e(TAG, "Error closing CSVReader file: " + e);
				}
			}
		}

		Log.d(TAG, "Servers: " + serverList.size());

		return serverList;
	}
	
	private void insertServerListToDatabase(List<Server> servers){
		dataSource.open();
		for(int i=0; i<servers.size(); i++){
			dataSource.createServer(servers.get(i));
		}
		dataSource.close();
	}
	
	/**
	 * Automatically detects the correct OTP server based on the location of the device
	 * @param currLoc location of the device
	 * @return Server the OTP server that the location is within
	 */
	private Server findOptimalSever(LatLng currentLocation) {
		if(currentLocation == null){
			return null;
		}
		
		//If we've already selected a server, just return the one we selected
		if(selectedServer != null) {
			return selectedServer;
		}

		boolean isInBoundingBox = false;
		Server server = null;
		for (int i=0; i<knownServers.size(); i++) {
			// Check bounds here to find server - acceptable error is set to 1000m = 1km
			isInBoundingBox = LocationUtil.checkPointInBoundingBox(currentLocation, knownServers.get(i), 1000);
			
			if(isInBoundingBox){
				server = knownServers.get(i);
				break;
			}
		}

		return server;
	}

	protected void onPostExecute(Long result) {
		if ((activity.get() != null) && showDialog){
			try{
				if (progressDialog != null && progressDialog.isShowing()) {
					progressDialog.dismiss();
				}
			}catch(Exception e){
				Log.e(TAG, "Error in Server Selector PostExecute dismissing dialog: " + e);
			}
		}
        
		if (selectedServer != null) {
			//We've already auto-selected a server
			SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
			long serverId = prefs.getLong(OTPApp.PREFERENCE_KEY_SELECTED_SERVER, 0);
			Server s = null;
			boolean serverIsChanged = true;
			if (serverId != 0){
				dataSource.open();
				s = dataSource.getServer(prefs.getLong(OTPApp.PREFERENCE_KEY_SELECTED_SERVER, 0));
				dataSource.close();
			}
			if (s != null){
				serverIsChanged = !(s.getRegion().equals(selectedServer.getRegion()));
			}
			if (showDialog || serverIsChanged){
				Toast.makeText(context.getApplicationContext(), 
					context.getResources().getString(R.string.server_selector_detected) + " "+selectedServer.getRegion() + ". " + context.getResources().getString(R.string.server_selector_server_change_info), 
						   Toast.LENGTH_SHORT).show();
			}
			Editor e = prefs.edit();
			e.putLong(PREFERENCE_KEY_SELECTED_SERVER, selectedServer.getId());
			e.putBoolean(PREFERENCE_KEY_SELECTED_CUSTOM_SERVER, false);
			e.commit();
			callback.onServerSelectorComplete(selectedServer);
		} else if (knownServers != null && !knownServers.isEmpty()){
			Log.d(TAG, "No server automatically selected.  User will need to choose the OTP server.");
			
			// Create dialog for user to choose
			List<String> serverNames = new ArrayList<String>();
			for (Server server : knownServers) {
				serverNames.add(server.getRegion());
			}
			
			Collections.sort(serverNames);
			
			serverNames.add(0,context.getResources().getString(R.string.custom_server_name));

			final CharSequence[] items = serverNames.toArray(new CharSequence[serverNames.size()]);
			
			if (activity.get() != null){
				AlertDialog.Builder builder = new AlertDialog.Builder(activity.get() );
				builder.setTitle(context.getResources().getString(R.string.server_selector_server_info_dialog_title));
				builder.setItems(items, new DialogInterface.OnClickListener() {
	
					public void onClick(DialogInterface dialog, int item) {
	
						//If the user selected to enter a custom URL, they are shown this EditText box to enter it
						if(items[item].equals(context.getResources().getString(R.string.custom_server_name))) {
							SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
	
							final EditText tbBaseURL = new EditText(activity.get());
							String actualCustomServer = prefs.getString(PREFERENCE_KEY_CUSTOM_SERVER_URL, "");
							tbBaseURL.setText(actualCustomServer);
	
							AlertDialog.Builder urlAlert = new AlertDialog.Builder(activity.get());
							urlAlert.setTitle(context.getResources().getString(R.string.server_selector_custom_server_alert_title));
							urlAlert.setView(tbBaseURL);
							urlAlert.setPositiveButton(context.getResources().getString(android.R.string.ok), new DialogInterface.OnClickListener() {
								public void onClick(DialogInterface dialog, int whichButton) {
									String value = tbBaseURL.getText().toString().trim();
									if (URLUtil.isValidUrl(value)){
										SharedPreferences.Editor prefsEditor = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext()).edit();
										prefsEditor.putString(PREFERENCE_KEY_CUSTOM_SERVER_URL, value);
	
										ServerChecker serverChecker = new ServerChecker(activity, context, ServerSelector.this, false);
										serverChecker.execute(new Server(value, context));
										prefsEditor.commit();
									}
									else{
										Toast.makeText(context, context.getResources().getString(R.string.custom_server_url_error), Toast.LENGTH_SHORT).show();
									}
								}
							});
							selectedCustomServer = true;
							urlAlert.create().show();
						} else { 
							//User picked server from the list
							for (Server server : knownServers) {
								//If this server region matches what the user picked, then set the server as the selected server
								if (server.getRegion().equals(items[item])) {
									selectedServer = server;
									SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
									Editor e = prefs.edit();
									e.putLong(PREFERENCE_KEY_SELECTED_SERVER, selectedServer.getId());
									e.putBoolean(PREFERENCE_KEY_SELECTED_CUSTOM_SERVER, false);
									e.commit();
									callback.onServerSelectorComplete(selectedServer);
									break;
								}
							}
						}
						Log.v(TAG, "Chosen: " + items[item]);
					}
				});
				builder.show();
			}
		} else {
			//TODO - handle error here that server list cannot be loaded
			Log.e(TAG, "Server list could not be downloaded!!");
		}
	}

	@Override
	public void onServerCheckerComplete(String result, boolean isWorking) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
		SharedPreferences.Editor prefsEditor = prefs.edit();
		if (isWorking){
			prefsEditor.putBoolean(PREFERENCE_KEY_AUTO_DETECT_SERVER, false);
			prefsEditor.putBoolean(PREFERENCE_KEY_SELECTED_CUSTOM_SERVER, true);
			prefsEditor.putBoolean(PREFERENCE_KEY_CUSTOM_SERVER_URL_IS_VALID, true);
			prefsEditor.commit();
			if (selectedCustomServer){
				String baseURL = prefs.getString(PREFERENCE_KEY_CUSTOM_SERVER_URL, "");
				selectedServer = new Server(baseURL, context);
				callback.onServerSelectorComplete(selectedServer);
			}
		}
		else{
			prefsEditor.putBoolean(PREFERENCE_KEY_CUSTOM_SERVER_URL_IS_VALID, false);
			prefsEditor.putBoolean(PREFERENCE_KEY_SELECTED_CUSTOM_SERVER, false);
			Toast.makeText(context, context.getResources().getString(R.string.custom_server_not_set), Toast.LENGTH_SHORT).show();
		}	
		
		prefsEditor.commit();
	}
}
