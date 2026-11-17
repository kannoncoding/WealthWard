package io.github.kannoncoding.wealthward.notifications

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import io.github.kannoncoding.wealthward.MainActivity
import java.time.*
import org.json.JSONArray
import org.json.JSONObject

private const val UPCOMING_CHANNEL = "upcoming"

data class ReminderItem(val id: String, val title: String, val date: String, val detail: String)
data class ReminderPreferences(val enabled: Boolean = false, val leadDays: Int = 1, val hour: Int = 9)

/** Desired alarms are persisted so reboot and permission changes can reconcile them. */
class LocalReminders internal constructor(
 private val context: Context,
 private val permissionGranted: () -> Boolean,
) {
 constructor(context: Context) : this(context, {
  Build.VERSION.SDK_INT < 33 ||
   context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
 })

 private val prefs = context.getSharedPreferences("local-reminders", Context.MODE_PRIVATE)
 private val alarms = context.getSystemService(AlarmManager::class.java)
 private val manager = context.getSystemService(NotificationManager::class.java)
 fun preferences() = ReminderPreferences(prefs.getBoolean("enabled", false), prefs.getInt("lead", 1), prefs.getInt("hour", 9))
 fun allowed() =
  permissionGranted() && manager.areNotificationsEnabled() &&
   manager.getNotificationChannel(UPCOMING_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
 fun save(value: ReminderPreferences) {
  require(value.leadDays in 0..31 && value.hour in 0..23) { "Usa anticipación 0–31 y hora 0–23." }
  prefs.edit().putBoolean("enabled", value.enabled).putInt("lead", value.leadDays).putInt("hour", value.hour).apply()
  reconcile()
 }
 fun update(items: List<ReminderItem>) {
  val previous = stored()
  previous.forEach { alarms.cancel(pending(it.id)); if(items.none { next -> next.id == it.id && next == it }) manager.cancel(it.id, 1) }
  val rows = JSONArray()
  items.distinctBy { it.id }.forEach { rows.put(JSONObject().put("id", it.id).put("title", it.title).put("date", it.date).put("detail", it.detail)) }
  prefs.edit().putString("items", rows.toString()).apply()
  reconcile()
 }
 private fun stored(): List<ReminderItem> {
  val rows = JSONArray(prefs.getString("items", "[]"))
  return (0 until rows.length()).map { rows.getJSONObject(it).let { row -> ReminderItem(row.getString("id"), row.getString("title"), row.getString("date"), row.getString("detail")) } }
 }
 private fun pending(id: String) = PendingIntent.getBroadcast(context, 0, Intent(context, ReminderReceiver::class.java).setAction("wealthward.reminder").setData(android.net.Uri.parse("wealthward://reminder/${android.net.Uri.encode(id)}")).putExtra("id", id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
 fun reconcile() {
  val settings = preferences()
  stored().forEach { item ->
   val intent = pending(item.id)
   alarms.cancel(intent)
   if(!settings.enabled) manager.cancel(item.id, 1)
   if (settings.enabled && allowed() && !prefs.getBoolean("sent:${item.id}:${item.date}", false)) {
    val at = LocalDate.parse(item.date).minusDays(settings.leadDays.toLong()).atTime(settings.hour, 0).atZone(ZoneId.of("America/Costa_Rica")).toInstant().toEpochMilli()
    // Android may delay delivery; never requests exact-alarm access.
    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, maxOf(at, System.currentTimeMillis() + 1000), intent)
   }
  }
 }
 fun deliver(id: String) {
  if (!preferences().enabled || !allowed()) return
  val item = stored().find { it.id == id } ?: return
  val key = "sent:${item.id}:${item.date}"
  if (prefs.getBoolean(key, false)) return
  if(show(item.id, item.title, item.detail)) prefs.edit().putBoolean(key, true).apply()
 }
 fun test(): Boolean { if (!allowed()) return false; return show("test", "Prueba de recordatorio", "Los recordatorios locales están disponibles.") }
 private fun show(id: String, title: String, detail: String): Boolean = try {
  manager.createNotificationChannel(NotificationChannel(UPCOMING_CHANNEL, "Próximos pagos e ingresos", NotificationManager.IMPORTANCE_DEFAULT))
  val launch = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  manager.notify(id, 1, Notification.Builder(context, UPCOMING_CHANNEL).setSmallIcon(io.github.kannoncoding.wealthward.R.drawable.ic_wealthward).setContentTitle(title).setContentText(detail).setStyle(Notification.BigTextStyle().bigText(detail)).setContentIntent(launch).setAutoCancel(true).build())
  true
 } catch (_: SecurityException) { false }
}
class ReminderReceiver : BroadcastReceiver() {
 override fun onReceive(context: Context, intent: Intent) { if(intent.action=="wealthward.reminder") LocalReminders(context).deliver(intent.getStringExtra("id") ?: return) }
}
class ReminderBootReceiver : BroadcastReceiver() {
 override fun onReceive(context: Context, intent: Intent) { if(intent.action==Intent.ACTION_BOOT_COMPLETED || intent.action==Intent.ACTION_MY_PACKAGE_REPLACED) LocalReminders(context).reconcile() }
}
