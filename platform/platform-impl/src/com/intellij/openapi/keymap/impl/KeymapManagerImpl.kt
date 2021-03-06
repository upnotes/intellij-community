// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.keymap.impl

import com.intellij.configurationStore.LazySchemeProcessor
import com.intellij.configurationStore.SchemeDataHolder
import com.intellij.ide.WelcomeWizardUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ConfigImportHelper
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.editor.actions.CtrlYActionChooser
import com.intellij.openapi.keymap.Keymap
import com.intellij.openapi.keymap.KeymapManagerListener
import com.intellij.openapi.keymap.ex.KeymapManagerEx
import com.intellij.openapi.options.SchemeManager
import com.intellij.openapi.options.SchemeManagerFactory
import com.intellij.openapi.util.text.NaturalComparator
import com.intellij.ui.AppUIUtil
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.containers.SmartHashSet
import gnu.trove.THashMap
import org.jdom.Element
import java.util.*
import java.util.function.Function
import java.util.function.Predicate

internal const val KEYMAPS_DIR_PATH = "keymaps"

private const val ACTIVE_KEYMAP = "active_keymap"
private const val NAME_ATTRIBUTE = "name"

@State(name = "KeymapManager", storages = [(Storage(value = "keymap.xml", roamingType = RoamingType.PER_OS))], additionalExportFile = KEYMAPS_DIR_PATH)
class KeymapManagerImpl(defaultKeymap: DefaultKeymap, factory: SchemeManagerFactory) : KeymapManagerEx(), PersistentStateComponent<Element> {
  private val listeners = ContainerUtil.createLockFreeCopyOnWriteList<KeymapManagerListener>()
  private val boundShortcuts = THashMap<String, String>()
  private val schemeManager: SchemeManager<Keymap>

  companion object {
    @JvmStatic
    var isKeymapManagerInitialized = false
      private set
  }

  init {
    schemeManager = factory.create(KEYMAPS_DIR_PATH, object : LazySchemeProcessor<Keymap, KeymapImpl>() {
      override fun createScheme(dataHolder: SchemeDataHolder<KeymapImpl>,
                                name: String,
                                attributeProvider: Function<in String, String?>,
                                isBundled: Boolean) = KeymapImpl(name, dataHolder)
      override fun onCurrentSchemeSwitched(oldScheme: Keymap?, newScheme: Keymap?) {
        fireActiveKeymapChanged(newScheme)
      }

      override fun reloaded(schemeManager: SchemeManager<Keymap>, schemes: Collection<Keymap>) {
        if (schemeManager.activeScheme == null) {
          // listeners expect that event will be fired in EDT
          AppUIUtil.invokeOnEdt {
            schemeManager.setCurrentSchemeName(defaultKeymap.defaultKeymapName, true)
          }
        }
      }
    })

    val systemDefaultKeymap = if (WelcomeWizardUtil.getWizardMacKeymap() == null) defaultKeymap.defaultKeymapName else WelcomeWizardUtil.getWizardMacKeymap()
    for (keymap in defaultKeymap.keymaps) {
      schemeManager.addScheme(keymap)
      if (keymap.name == systemDefaultKeymap) {
        activeKeymap = keymap
      }
    }
    schemeManager.loadSchemes()

    isKeymapManagerInitialized = true

    if (ConfigImportHelper.isFirstSession() && !ConfigImportHelper.isConfigImported()) {
      CtrlYActionChooser.askAboutShortcut()
    }
  }

  private fun fireActiveKeymapChanged(newScheme: Keymap?) {
    ApplicationManager.getApplication().messageBus.syncPublisher(KeymapManagerListener.TOPIC).activeKeymapChanged(activeKeymap)
    for (listener in listeners) {
      listener.activeKeymapChanged(newScheme)
    }
  }

  override fun getAllKeymaps(): Array<Keymap> = getKeymaps(null).toTypedArray()

  fun getKeymaps(additionalFilter: Predicate<Keymap>?): List<Keymap> {
    return schemeManager.allSchemes.filter { !it.presentableName.startsWith("$") && (additionalFilter == null || additionalFilter.test(it)) }
  }

  override fun getKeymap(name: String): Keymap? = schemeManager.findSchemeByName(name)

  override fun getActiveKeymap(): Keymap? = schemeManager.activeScheme

  override fun setActiveKeymap(keymap: Keymap?): Unit = schemeManager.setCurrent(keymap)

  override fun bindShortcuts(sourceActionId: String, targetActionId: String) {
    boundShortcuts.put(targetActionId, sourceActionId)
  }

  override fun unbindShortcuts(targetActionId: String) {
    boundShortcuts.remove(targetActionId)
  }

  override fun getBoundActions(): MutableSet<String> = boundShortcuts.keys

  override fun getActionBinding(actionId: String): String? {
    var visited: MutableSet<String>? = null
    var id = actionId
    while (true) {
      val next = boundShortcuts.get(id) ?: break
      if (visited == null) {
        visited = SmartHashSet()
      }

      id = next
      if (!visited.add(id)) {
        break
      }
    }
    return if (id == actionId) null else id
  }

  override fun getSchemeManager(): SchemeManager<Keymap> = schemeManager

  fun setKeymaps(keymaps: List<Keymap>, active: Keymap?, removeCondition: Predicate<Keymap>?) {
    schemeManager.setSchemes(keymaps, active, removeCondition)
    fireActiveKeymapChanged(active)
  }

  override fun getState(): Element {
    val result = Element("state")
    schemeManager.activeScheme?.let {
      if (it.name != DefaultKeymap.instance.defaultKeymapName) {
        val e = Element(ACTIVE_KEYMAP)
        e.setAttribute(NAME_ATTRIBUTE, it.name)
        result.addContent(e)
      }
    }
    return result
  }

  override fun loadState(state: Element) {
    val child = state.getChild(ACTIVE_KEYMAP)
    val activeKeymapName = child?.getAttributeValue(NAME_ATTRIBUTE)
    if (!activeKeymapName.isNullOrBlank()) {
      schemeManager.currentSchemeName = activeKeymapName
    }
  }

  @Suppress("OverridingDeprecatedMember")
  override fun addKeymapManagerListener(listener: KeymapManagerListener) {
    pollQueue()
    listeners.add(listener)
  }

  @Suppress("DEPRECATION", "OverridingDeprecatedMember")
  override fun addKeymapManagerListener(listener: KeymapManagerListener, parentDisposable: Disposable) {
    pollQueue()
    ApplicationManager.getApplication().messageBus.connect(parentDisposable).subscribe(KeymapManagerListener.TOPIC, listener)
  }

  private fun pollQueue() {
    listeners.removeAll { it is WeakKeymapManagerListener && it.isDead }
  }

  @Suppress("OverridingDeprecatedMember")
  override fun removeKeymapManagerListener(listener: KeymapManagerListener) {
    pollQueue()
    listeners.remove(listener)
  }

  @Suppress("DEPRECATION")
  override fun addWeakListener(listener: KeymapManagerListener) {
    pollQueue()
    listeners.add(WeakKeymapManagerListener(this, listener))
  }

  override fun removeWeakListener(listenerToRemove: KeymapManagerListener) {
    listeners.removeAll { it is WeakKeymapManagerListener && it.isWrapped(listenerToRemove) }
  }

  fun fireShortcutChanged(keymap: Keymap, actionId: String) {
    ApplicationManager.getApplication().messageBus.syncPublisher(KeymapManagerListener.TOPIC).shortcutChanged(keymap, actionId)
  }
}

val keymapComparator: Comparator<Keymap?> by lazy {
  val defaultKeymapName = DefaultKeymap.instance.defaultKeymapName
  Comparator<Keymap?> { keymap1, keymap2 ->
    if (keymap1 === keymap2) return@Comparator 0
    if (keymap1 == null) return@Comparator - 1
    if (keymap2 == null) return@Comparator 1

    val parent1 = (if (!keymap1.canModify()) null else keymap1.parent) ?: keymap1
    val parent2 = (if (!keymap2.canModify()) null else keymap2.parent) ?: keymap2
    if (parent1 === parent2) {
      when {
        !keymap1.canModify() -> - 1
        !keymap2.canModify() -> 1
        else -> compareByName(keymap1, keymap2, defaultKeymapName)
      }
    }
    else {
      compareByName(parent1, parent2, defaultKeymapName)
    }
  }
}

private fun compareByName(keymap1: Keymap, keymap2: Keymap, defaultKeymapName: String): Int {
  return when (defaultKeymapName) {
    keymap1.name -> -1
    keymap2.name -> 1
    else -> NaturalComparator.INSTANCE.compare(keymap1.presentableName, keymap2.presentableName)
  }
}