package io.github.kannoncoding.wealthward.ui

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kannoncoding.wealthward.notifications.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/** Platform delivery and adapter behavior when notification permission is denied. */
class F06ReminderTest {
 private val instrumentation=InstrumentationRegistry.getInstrumentation()
 private val context=instrumentation.targetContext
 private fun shell(command:String) {
  instrumentation.uiAutomation.executeShellCommand(command).use { input -> android.os.ParcelFileDescriptor.AutoCloseInputStream(input).readBytes() }
 }
 private fun grantPermission() {
  if(Build.VERSION.SDK_INT>=33) shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
  shell("appops set --uid ${context.packageName} POST_NOTIFICATION allow")
  await("System notification gate did not become enabled") { LocalReminders(context).allowed() }
 }
 private fun await(message:String, condition:()->Boolean) {
  val deadline=android.os.SystemClock.elapsedRealtime()+5000
  while(!condition() && android.os.SystemClock.elapsedRealtime()<deadline) android.os.SystemClock.sleep(25)
  assertTrue(message,condition())
 }
 private fun removeSent(items:List<ReminderItem>) {
  val edit=context.getSharedPreferences("local-reminders",android.content.Context.MODE_PRIVATE).edit()
  items.forEach { edit.remove("sent:${it.id}:${it.date}") }
  edit.commit()
 }
 @Test fun denialPersistsPreferenceAndDoesNotPretendDelivery() {
  val adapter=LocalReminders(context) { false }
  val item=ReminderItem("denied-${UUID.randomUUID()}","Pago de prueba",LocalDate.now().plusDays(2).toString(),"Sin acceso requerido para el calendario")
  val manager=context.getSystemService(NotificationManager::class.java)
  try {
   adapter.save(ReminderPreferences(true,2,10))
   assertEquals(ReminderPreferences(true,2,10),LocalReminders(context).preferences())
   assertFalse(adapter.allowed()); assertFalse(adapter.test())
   adapter.update(listOf(item))
   adapter.reconcile()
   adapter.deliver(item.id)
   assertFalse(manager.activeNotifications.any { it.tag==item.id })
  } finally { adapter.update(emptyList()); adapter.save(ReminderPreferences()); removeSent(listOf(item)) }
 }
 @Test fun realSystemNotificationAndDisableCancelVisibleReminder() {
  grantPermission()
  val adapter=LocalReminders(context)
  val manager=context.getSystemService(NotificationManager::class.java)
  val item=ReminderItem("platform-${UUID.randomUUID()}","Pago de prueba",LocalDate.now().plusDays(1).toString(),"CRC 10.00")
  val disableItem=item.copy(id="disable-${UUID.randomUUID()}")
  try {
   assertTrue(adapter.allowed()); assertTrue(adapter.test())
   await("Test notification was not posted") { manager.activeNotifications.any { it.tag=="test" } }
   adapter.update(listOf(item)); adapter.save(ReminderPreferences(true)); adapter.deliver(item.id)
   await("Reminder was not posted") { manager.activeNotifications.any { it.tag==item.id } }
   manager.cancel(item.id,1)
   await("Reminder was not canceled") { manager.activeNotifications.none { it.tag==item.id } }
   adapter.deliver(item.id)
   // A second delivery attempt must not resurrect a dismissed notification.
   assertTrue(manager.activeNotifications.none { it.tag==item.id })
   adapter.update(listOf(disableItem)); adapter.deliver(disableItem.id)
   await("Second reminder was not posted") { manager.activeNotifications.any { it.tag==disableItem.id } }
   adapter.save(ReminderPreferences(false))
   await("Disabled reminder remained visible") { manager.activeNotifications.none { it.tag==disableItem.id } }
   adapter.update(emptyList())
  } finally { adapter.save(ReminderPreferences()); adapter.update(emptyList()); manager.cancel("test",1); manager.cancel(item.id,1); manager.cancel(disableItem.id,1); removeSent(listOf(item,disableItem)) }
 }
}
