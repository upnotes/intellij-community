/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.testGuiFramework.impl;

import com.intellij.ide.GeneralSettings;
import com.intellij.testGuiFramework.framework.GuiTestBase;
import com.intellij.testGuiFramework.framework.GuiTestUtil;
import com.intellij.util.net.HttpConfigurable;
import org.fest.swing.core.SmartWaitRobot;

import java.lang.reflect.InvocationTargetException;


/**
 * @author Sergey Karashevich
 */
public class GuiTestCase extends GuiTestBase {

  public static final boolean IS_UNDER_TEAMCITY = System.getenv("TEAMCITY_VERSION") != null;

  public GuiTestCase() {
    super();
  }

  public static class GuiSettings {

    private static final Object lock = new Object();

    private static GuiSettings SETTINGS;

    public static GuiSettings setUp() {
      synchronized (lock) {
        if (SETTINGS == null) SETTINGS = new GuiSettings();
        return SETTINGS;
      }
    }

    GuiSettings(){
      GeneralSettings.getInstance().setShowTipsOnStartup(false);
      GuiTestUtil.setUpDefaultProjectCreationLocationPath();
      GuiTestUtil.setUpSdks();
      HttpConfigurable ideSettings = HttpConfigurable.getInstance();
      ideSettings.USE_HTTP_PROXY = false;
      ideSettings.PROXY_HOST = "";
      ideSettings.PROXY_PORT = 80;
      if (IS_UNDER_TEAMCITY) GitSettings.INSTANCE.setup();
    }

  }

    @Override
    public void setUp() throws Exception {
      super.setUp();
      myRobot = new SmartWaitRobot();
      GuiSettings.setUp();
    }

    @Override
    public void tearDown() throws InvocationTargetException, InterruptedException {
      GitSettings.INSTANCE.restore();
      super.tearDown();
    }

  }
