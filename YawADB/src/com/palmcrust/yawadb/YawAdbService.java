/* 
   YawAdbService. Service used by YawADB widget (API 3+)   
   Copyright (C) 2013 Michael Glickman (Australia) <palmcrust@gmail.com>

   This program is free software: you can redistribute it and/or modify
   it under the terms of the GNU General Public License as published by
   the Free Software Foundation, either version 3 of the License, or
   (at your option) any later version.

   This program is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU General Public License for more details.

   You should have received a copy of the GNU General Public License
   along with this program.  If not, see <http://www.gnu.org/licenses/>
*/    

package com.palmcrust.yawadb;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Message;
import android.widget.RemoteViews;

@TargetApi(Build.VERSION_CODES.CUPCAKE)
public class YawAdbService extends Service {
	protected WidgetServiceThread servThread = null;
	private RemoteViews views;
	private ComponentName providerCompName; 
	private AppWidgetManager appWidgetManager;
	private WidgetServiceBroadcastReceiver bcastReceiver;
	private int oldImageResId;
	private PendingIntent onClickIntent; 
	private String ifaceName;

	@Override
	public void onStart(Intent intent, int startId) {
	    handleStartCommand(intent);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
	    handleStartCommand(intent);
	    // We want this service to continue running until it is explicitly
	    // stopped, so return sticky.
	    return START_STICKY; 
	}
	
	private void handleStartCommand(Intent intent) {
		appWidgetManager = AppWidgetManager.getInstance(this);
		views = new RemoteViews(getPackageName(), R.layout.widget);
		providerCompName = intent.getParcelableExtra(YawAdbConstants.ComponentNameExtra);
		onClickIntent = intent.getParcelableExtra(YawAdbConstants.OnClickIntentExtra);
		oldImageResId = 0;
		
		processOptions();
		initRefreshStatus(true);
		
		bcastReceiver= new WidgetServiceBroadcastReceiver(this);
		IntentFilter filter = new IntentFilter();
		filter.addAction(YawAdbConstants.ProviderRefreshAction);
		filter.addAction(YawAdbConstants.RefreshStatusAction);
		filter.addAction(YawAdbConstants.OptionsChangedAction);
		filter.addAction(YawAdbConstants.PopupAction);
		filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED); 
		filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
		getApplicationContext().registerReceiver(bcastReceiver, filter);

	}
			
	protected void processOptions() {
		// We ALWAYS restart the thread in order to reset ticks!
		if (servThread != null) {
			servThread.terminate();
			servThread = null;
		}
		
		YawAdbOptions options = new YawAdbOptions(this);
		ifaceName = new String(options.ifaceName.getString());
		int refrInterval = options.getRefreshInterval();
		
		if (refrInterval > 0) {
			servThread = new WidgetServiceThread(this, refrInterval); 
			servThread.start();
		}
	}

	@Override
	public void onDestroy() {
		if (bcastReceiver != null) {
			getApplicationContext().unregisterReceiver(bcastReceiver);
			bcastReceiver = null;
		}
		
		if (servThread != null) {
			servThread.terminate();
			servThread = null;
		}
		super.onDestroy();
	}

	@Override
	public IBinder onBind(Intent arg0) {
		return null;
	}

	protected synchronized void initRefreshStatus(boolean force) {
		if (servThread != null)
			servThread.updateStatus(force);
		else
			refreshStatus(force);
	}
	
	protected synchronized void refreshStatus(boolean force) {
		StatusAnalyzer analyzer = new StatusAnalyzer(this, ifaceName); 
		int newImageResId = 
			(analyzer.analyze() == StatusAnalyzer.Status.UP) ? 
				R.drawable.wireless_up : R.drawable.wireless_down;
		analyzer = null;
		if (force || newImageResId != oldImageResId) {
			views.setImageViewResource(R.id.modeImg, newImageResId);
			appWidgetManager.updateAppWidget(providerCompName, views);
			oldImageResId = newImageResId; 
		}
	}

	protected synchronized void refreshOnClickListener() {
		views.setOnClickPendingIntent(R.id.modeImg, onClickIntent);
		appWidgetManager.updateAppWidget(providerCompName, views);
	}
	
	
	protected void startPopupActivity() {
		Intent intent = new Intent(this, PopupActivity.class);
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		intent.putExtra(YawAdbConstants.AsWidgetExtra, true);
		startActivity(intent);
	}

	//=========================================================================
	private static class WidgetServiceBroadcastReceiver extends BroadcastReceiver {
		private YawAdbService service; 
		public WidgetServiceBroadcastReceiver(YawAdbService service) {
			this.service = service;
		}
		
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if (action.equals(YawAdbConstants.ProviderRefreshAction)) {
				service.refreshOnClickListener();
				service.initRefreshStatus(true);
			} else	
			if (action.equals(YawAdbConstants.OptionsChangedAction)) 
				service.processOptions();
			else {
				service.initRefreshStatus(false);

				if (action.equals(YawAdbConstants.PopupAction)) 
					service.startPopupActivity();
				else 
				if (!action.equals(YawAdbConstants.RefreshStatusAction))  
					//	Intent.ACTION_AIRPLANE_MODE_CHANGED 
					//  ConnectivityManager.CONNECTIVITY_ACTION  
					service.refreshOnClickListener();
			}
				
		}
	}

	//=========================================================================
	private static class WidgetServiceMessageHandler extends android.os.Handler {
		protected static final int WHAT_SET_APPEARANCE = 1;
		private YawAdbService service;

		protected WidgetServiceMessageHandler(YawAdbService service) {
			super();
			this.service = service;
		}

		@Override
		public void handleMessage(Message msg) {
			if (msg.what == WHAT_SET_APPEARANCE) 
				service.refreshStatus(msg.arg1 != 0);
		}
			
	}
	//=========================================================================
	private static class WidgetServiceThread extends Thread {
		public static enum InterruptReason {UNDEFINED, UPDATE_STATUS, TERMINATE}
		public InterruptReason reason = InterruptReason.UNDEFINED;
		private WidgetServiceMessageHandler handler;
		private boolean force=false; 
		private int sleepTimeout; 
		
		protected WidgetServiceThread(YawAdbService service, int sleepTimeout) {
			super();
			handler = new WidgetServiceMessageHandler(service);
			this.sleepTimeout = sleepTimeout;
		}

		public void updateStatus(boolean force) {
			this.force = force;
			reason = InterruptReason.UPDATE_STATUS;
			interrupt();	
		}

		public void terminate() {
			reason = InterruptReason.TERMINATE;
			interrupt();	
		}
		
		public void run() {
			for (;;) {
				try {
					Message msg = Message.obtain(
						handler, WidgetServiceMessageHandler.WHAT_SET_APPEARANCE, force ? 1: 0, 0);
					msg.sendToTarget();
					force = false;
					Thread.sleep(sleepTimeout);
				} catch (InterruptedException ex) {
					if (reason != InterruptReason.UPDATE_STATUS)
						break;
				}
			}
		}
	}

}