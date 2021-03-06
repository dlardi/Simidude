/*
 * Copyright by AGYNAMIX(R). All rights reserved. 
 * This file is made available under the terms of the
 * license this product is released under.
 * 
 * For details please see the license file you should have
 * received, or go to:
 * 
 * http://www.agynamix.com
 * 
 * Contributors: agynamix.com (http://www.agynamix.com)
 */
package com.agynamix.simidude;

import java.io.IOException;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.agynamix.platform.frontend.gui.ApplicationGUI;
import com.agynamix.platform.frontend.gui.HotkeyRegistrarFactory;
import com.agynamix.platform.frontend.preferences.IPreferenceConstants;
import com.agynamix.platform.httpd.HTTPUtils;
import com.agynamix.platform.httpd.HttpServer;
import com.agynamix.platform.impl.QueueManagerImpl;
import com.agynamix.platform.infra.ApplicationBase;
import com.agynamix.platform.infra.ExecutorUtils;
import com.agynamix.platform.infra.FileUtils;
import com.agynamix.platform.infra.IQueueManager;
import com.agynamix.platform.infra.PlatformUtils;
import com.agynamix.platform.log.ApplicationLog;
import com.agynamix.platform.log.ConnectionLog;
import com.agynamix.platform.net.ClientNode;
import com.agynamix.simidude.clipboard.AutomaticDownloadHandler;
import com.agynamix.simidude.clipboard.ClipboardItemFactory;
import com.agynamix.simidude.clipboard.ClipboardMonitorFactory;
import com.agynamix.simidude.clipboard.IClipboardItem;
import com.agynamix.simidude.clipboard.IClipboardMonitor;
import com.agynamix.simidude.clipboard.SourceDataManager;
import com.agynamix.simidude.frontend.action.SimidudeHotkeyActions;
import com.agynamix.simidude.frontend.gui.SimidudeGUI;
import com.agynamix.simidude.impl.SimidudeApplicationContext;
import com.agynamix.simidude.infra.CacheManagerFactory;
import com.agynamix.simidude.infra.IItemActivationListener;
import com.agynamix.simidude.infra.ISimidudePreferences;
import com.agynamix.simidude.infra.ModelProvider;
import com.agynamix.simidude.remote.RemoteConnector;
import com.agynamix.simidude.source.ISourceData;
import com.agynamix.simidude.source.ISourceDataListener;
import com.agynamix.simidude.source.SourceQueueService;
import com.agynamix.simidude.source.ISourceData.TransportType;
import com.agynamix.simidude.source.impl.SourceListener;
import com.install4j.api.launcher.ApplicationLauncher;
import com.install4j.api.update.UpdateSchedule;
import com.install4j.api.update.UpdateScheduleRegistry;

public class Simidude extends ApplicationBase {

  SourceDataManager          sourceDataManager;
  IClipboardMonitor          clipboardMonitor;

  Logger                     log               = ApplicationLog.getLogger(Simidude.class);

  /**
   * Generated by install4j. Needs to match the one from the installer.
   */
  public final static String UPDATER_ID        = "288";
  public final static String MANUAL_UPDATER_ID = "322";

  /**
   * Step one in setting up the application. No other components might be
   * available.  So this step only creates stuff. Initialization is deferred.
   */
  @Override
  protected void applicationSetup()
  {
    IQueueManager qm = new QueueManagerImpl();
    getContext().registerService(IQueueManager.SERVICE_NAME, qm);

    SourceQueueService sourceDispatcher = new SourceQueueService("SourceQueueListener");
    sourceDispatcher.initialize();

    UUID senderId = getSenderId();
    ModelProvider m = new ModelProvider(senderId);
    getContext().registerService(ModelProvider.SERVICE_NAME, m);

    clipboardMonitor = ClipboardMonitorFactory.newClipboardMonitor();
    sourceDataManager = new SourceDataManager(clipboardMonitor);
    getContext().registerService(SourceDataManager.SERVICE_NAME, sourceDataManager);

    checkForUpdates();
  }

  private void checkForUpdates()
  {
    try
    {
      if (UpdateScheduleRegistry.getUpdateSchedule() == null)
      {
        UpdateScheduleRegistry.setUpdateSchedule(UpdateSchedule.ON_EVERY_START);
      }
      if (UpdateScheduleRegistry.checkAndReset())
      {
        ApplicationLauncher.launchApplication(UPDATER_ID, null, false, new ApplicationLauncher.Callback() {
          public void exited(int exitValue) { }

          public void prepareShutdown()
          {
            try
            {
              ApplicationGUI gui = ApplicationBase.getContext().getApplicationGUI();
              gui.close();
            } catch (Exception ignore)
            {
            }
          }
        });
      }
    } catch (Exception ignore) { }
  }

  private UUID getSenderId()
  {
    UUID senderId;
    String senderIdStr = getContext().getConfiguration().getProperty(ISimidudePreferences.SENDER_ID);
    if ((senderIdStr == null) || (senderIdStr.length() == 0))
    {
      senderId = UUID.randomUUID();
      getContext().getConfiguration().setProperty(ISimidudePreferences.SENDER_ID, senderId.toString());
    } else
    {
      senderId = UUID.fromString(senderIdStr);
    }
    return senderId;
  }

  /**
   * Step Two in setting up the application. All referenced components should be
   * available.
   */
  @Override
  protected void applicationInitialize()
  {
    SourceListener sourceListener = new SourceListener("ClipboardListener", clipboardMonitor);
    sourceListener.initialize();
    SourceQueueService sourceQueueService = (SourceQueueService) ApplicationBase.getContext().getService(SourceQueueService.SERVICE_NAME);
    sourceQueueService.addSourceDataListener(sourceDataManager);

    sourceQueueService.addSourceDataListener(new ISourceDataListener() {
      
      public void sourceDataChanged(ISourceData data)
      {
        conditionalSaveClipboardItem(data, true);
      }
    });
    
    sourceDataManager.addItemActivationListener(new IItemActivationListener() {      
      public void itemActivated(IClipboardItem item)
      {
        if (item != null)
        {
          conditionalSaveClipboardItem(item.getSourceData(), false);
        }  
      }
    });

  }
  
  private void conditionalSaveClipboardItem(ISourceData data, boolean onlyOwnItems)
  {
    if ((data != null) && (ApplicationBase.getContext().getConfiguration().getBoolean(IPreferenceConstants.RESTORE_LATEST_ENTRY)))
    {
      ClientNode myOwnNode = ((SimidudeApplicationContext)ApplicationBase.getContext()).getRemoteConnector().getConnector().getMyOwnNode();
      if (((data.getSenderId().equals(myOwnNode.getNodeId())) && (data.getTransportType() == TransportType.local)) || (!onlyOwnItems))
      {
        FileUtils.serialize(data, PlatformUtils.getApplicationDataDir() + "/" + IPreferenceConstants.SAVED_CLP_ITEM_FILE_NAME);
      }
    }      
  }

  @Override
  protected void startup()
  {
  }

  @Override
  protected void shutdown()
  {
    RemoteConnector connector = ((SimidudeApplicationContext) getContext()).getRemoteConnector();
    connector.shutdown();
    HotkeyRegistrarFactory.getHotkeyRegistrarInstance().unregisterHotkeys();
    super.shutdown();
  }

  @Override
  protected void prepareRun()
  {
    final ModelProvider m = (ModelProvider) getContext().getService(ModelProvider.SERVICE_NAME);
    RemoteConnector connector = new RemoteConnector();
    connector.initializeConnector(m);
    connector.establishConnection();
    ApplicationBase.getContext().registerService(RemoteConnector.SERVICE_NAME, connector);
    connector.contactPermanentNetworkAddresses();

    // Startup HTTPD
    checkHTTPDStart();

    // Check if we should load a saved Clipboard Item
    checkRestoreClipboardItem();

    // Register a HotKey listener
    SimidudeHotkeyActions.registerHotkeys(); 
        
    AutomaticDownloadHandler downloadHandler = 
      new AutomaticDownloadHandler(((SimidudeApplicationContext)ApplicationBase.getContext()).getSourceDataManager(), m.getSenderId());
    ApplicationBase.getContext().registerService(AutomaticDownloadHandler.SERVICE_NAME, downloadHandler);
    
    // Register a CacheCleaner Thread
    ExecutorUtils.addScheduledService(CacheManagerFactory.newCacheCleaner(), 5, 3600, TimeUnit.SECONDS);
    
    // Register a ConnectionLog, an object that keeps track of sent data. Currently used for diagnostic purposes
    initializeConnectionLog();
    
  }

  private void checkHTTPDStart() { 
    if (ApplicationBase.getContext().getConfiguration().getBoolean(IPreferenceConstants.START_HTTP_SERVER))
    {
      log.config("Try to start HTTP server.");
      HttpServer nh = null;
      Properties env = new Properties();
      env.setProperty(HTTPUtils.HTTP_PORT, ApplicationBase.getContext().getConfiguration().getProperty(
          IPreferenceConstants.HTTP_SERVER_PORT));
      env.setProperty(HTTPUtils.HTTP_USER, ApplicationBase.getContext().getConfiguration().getProperty(
          IPreferenceConstants.NODE_GROUP_NAME));
      env.setProperty(HTTPUtils.HTTP_PASSWORD, ApplicationBase.getContext().getConfiguration().getProperty(
          IPreferenceConstants.NODE_GROUP_PWD));
      try
      {
        nh = new HttpServer(env);
        log.config("Start HTTP server at port "
            + ApplicationBase.getContext().getConfiguration().getProperty(IPreferenceConstants.HTTP_SERVER_PORT));
      } catch (IOException ioe)
      {
        log.log(Level.WARNING, "Could not start HTTP server.", ioe);
      }
    } else
    {
      log.config("HTTP server is configured not to be started.");
    }
  }

  private void initializeConnectionLog()
  {
    ConnectionLog connectionLog = new ConnectionLog();
    SourceQueueService qs = (SourceQueueService) ApplicationBase.getContext().getService(SourceQueueService.SERVICE_NAME);
    qs.addSourceDataListener(connectionLog);
    ApplicationBase.getContext().registerService(ConnectionLog.SERVICE_NAME, connectionLog);
  }
  
  private void checkRestoreClipboardItem() {
    try
    {
      SourceDataManager sdm = ((SimidudeApplicationContext) ApplicationBase.getContext()).getSourceDataManager();
      // sdm.emptyClipboard();
      if (ApplicationBase.getContext().getConfiguration().getBoolean(IPreferenceConstants.RESTORE_LATEST_ENTRY))
      {
        // System.out.println("Should restore latest entry");
        if (sdm.isClipboardMonitorEnabled())
        {
          if (sdm.isClipboardEmpty())
          {
            // System.out.println("Clipboard is empty");
            ISourceData sourceData = (ISourceData) FileUtils.deserialize(PlatformUtils.getApplicationDataDir() + "/"
                + IPreferenceConstants.SAVED_CLP_ITEM_FILE_NAME);
            if (sourceData != null)
            {
              IClipboardItem item = ClipboardItemFactory.createItemFromSourceData(sdm, sourceData);
              ((SimidudeApplicationContext) ApplicationBase.getContext()).getQueueManager().put(
                  IQueueManager.QUEUE_SOURCE_DATA_MONITOR, sourceData);
              // sdm.sourceDataChanged(sourceData);
              sdm.activateItem(item);
            }
          }
        }
      }
    } catch (Exception e)
    {
      log.log(Level.WARNING, "A saved clipboard contents file could not be restored.", e);
    }
  }

  
  // FIXME: register RemoteConnector to ClipboardEvents, when event occurs
  // check if we have just put this item on the clipboard: yes => nothing
  // no:
  // - Client: Send the item to the server
  // - Server: Send a notification to the clients

  // FIXME:
  // Server: Receives a new item from a client
  // - put item into InputQueue

  // Client: Receives a notification from the server
  // - NotificationFilter: only events that originated somewhere else
  //   
  // - put item into InputQueue. !!!Item must be marked as received remotely,
  // so that item will not be resent to the server!!!

  /**
   * Creates an implementation of ApplicationGUI which implements this
   * appplications specifics. An ApplicationGUI extends the JFace
   * ApplicationWindow class and is the primary constructor of the visible part
   * of the user interface. Called only once.
   * 
   */
  @Override
  protected ApplicationGUI createApplicationGUI()
  {
    return new SimidudeGUI();
  }

  /**
   * Launch this application
   * 
   * @param args
   */
  public static void main(String[] args)
  {
    launch(Simidude.class, new SimidudeApplicationContext(), args);
  }

}
