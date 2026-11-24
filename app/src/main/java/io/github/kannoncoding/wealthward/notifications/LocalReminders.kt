package io.github.kannoncoding.wealthward.notifications

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import io.github.kannoncoding.wealthward.MainActivity
import io.github.kannoncoding.wealthward.ui.AppLanguage
import java.time.*
import org.json.JSONArray
import org.json.JSONObject

private const val UPCOMING_CHANNEL = "upcoming"

data class ReminderItem(val id: String, val title: String, val date: String, val detail: String)
data class ReminderPreferences(val enabled: Boolean = false, val leadDays: Int = 1, val hour: Int = 9, val additionalDays: List<Int> = emptyList()) {
 val leads get() = listOfNotNull(leadDays.takeIf { it >= 0 }) + additionalDays
}

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
 fun preferences() = ReminderPreferences(prefs.getBoolean("enabled", false), prefs.getInt("lead", 1), prefs.getInt("hour", 9), prefs.getString("additional-leads", "").orEmpty().split(",").mapNotNull(String::toIntOrNull))
 fun allowed() =
  permissionGranted() && manager.areNotificationsEnabled() &&
   manager.getNotificationChannel(UPCOMING_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
 fun save(value: ReminderPreferences) {
  require(value.leadDays in -1..31 && value.leads.all { it in 0..31 } && value.leads.distinct().size == value.leads.size && value.hour in 0..23) { "Usa anticipación 0–31 y hora 0–23." }
  val previous = preferences()
  if (!prefs.contains("legacy-lead")) prefs.edit().putInt("legacy-lead", previous.leadDays).apply()
  if (previous != value) stored().forEach { cancel(it, previous, true) }
  prefs.edit().putString("additional-leads", value.additionalDays.joinToString(",")).putBoolean("enabled", value.enabled).putInt("lead", value.leadDays).putInt("hour", value.hour).apply()
  reconcile()
 }
 fun update(items: List<ReminderItem>) {
  val previous = stored()
  val settings = preferences()
  previous.forEach { item -> cancel(item, settings, items.none { it == item }) }
  val rows = JSONArray()
  items.distinctBy { it.id }.forEach { rows.put(JSONObject().put("id", it.id).put("title", it.title).put("date", it.date).put("detail", it.detail)) }
  prefs.edit().putString("items", rows.toString()).apply()
  reconcile()
 }
 private fun stored(): List<ReminderItem> {
  val rows = JSONArray(prefs.getString("items", "[]"))
  return (0 until rows.length()).map { rows.getJSONObject(it).let { row -> ReminderItem(row.getString("id"), row.getString("title"), row.getString("date"), row.getString("detail")) } }
 }
 private fun pending(item: ReminderItem, lead: Int? = null): PendingIntent {
  val uri = android.net.Uri.Builder().scheme("wealthward").authority("reminder").appendPath(item.id)
  if (lead != null) uri.appendPath(item.date).appendPath(lead.toString())
  val intent = Intent(context, ReminderReceiver::class.java).setAction("wealthward.reminder")
   .setData(uri.build()).putExtra("id", item.id).putExtra("due", item.date).putExtra("lead", lead ?: -1)
  return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
 }
 private fun tag(item: ReminderItem, lead: Int, settings: ReminderPreferences) =
  if (lead == settings.leadDays) item.id else item.id + ":lead:" + lead
 private fun cancel(item: ReminderItem, settings: ReminderPreferences, notifications: Boolean) {
  alarms.cancel(pending(item))
  settings.leads.forEach { lead ->
   alarms.cancel(pending(item, lead))
   if (notifications) manager.cancel(tag(item, lead, settings), 1)
  }
 }
 private fun sentKey(item: ReminderItem, lead: Int) = "sent:" + item.id + ":" + item.date + ":" + lead
 private fun alreadySent(item: ReminderItem, lead: Int, settings: ReminderPreferences): Boolean =
  prefs.getBoolean(sentKey(item, lead), false) ||
   (lead == prefs.getInt("legacy-lead", settings.leadDays) && prefs.getBoolean("sent:" + item.id + ":" + item.date, false))
 fun reconcile() {
  val settings = preferences()
  if (!prefs.contains("legacy-lead")) prefs.edit().putInt("legacy-lead", settings.leadDays).apply()
  val now = ZonedDateTime.now(ZoneId.of("America/Costa_Rica"))
  stored().forEach { item ->
   cancel(item, settings, !settings.enabled)
   if (!settings.enabled || !allowed()) return@forEach
   val due = LocalDate.parse(item.date)
   settings.leads.forEach leadLoop@{ lead ->
    val local = due.minusDays(lead.toLong())
    if (local < now.toLocalDate() || alreadySent(item, lead, settings)) return@leadLoop
    val at = local.atTime(settings.hour, 0).atZone(now.zone).toInstant().toEpochMilli()
    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, maxOf(at, System.currentTimeMillis() + 1000), pending(item, lead))
   }
  }
 }
 fun deliver(id: String, expectedDate: String? = null, expectedLead: Int? = null) {
  val settings = preferences()
  if (!settings.enabled || !allowed()) return
  val item = stored().find { it.id == id } ?: return
  val lead = expectedLead ?: settings.leadDays
  if (expectedDate != null && item.date != expectedDate || lead !in settings.leads || alreadySent(item, lead, settings)) return
  if (show(tag(item, lead, settings), item.title, item.detail)) prefs.edit().putBoolean(sentKey(item, lead), true).apply()
 }
 fun test(): Boolean {
  if (!allowed()) return false
  val language = AppLanguage.read(context)
  return show("test", language.format("Prueba de recordatorio"), language.format("Los recordatorios locales están disponibles."))
 }
 private fun show(id: String, title: String, detail: String): Boolean = try {
  val titleForChannel = AppLanguage.read(context).format("Próximos pagos e ingresos")
  manager.createNotificationChannel(NotificationChannel(UPCOMING_CHANNEL, titleForChannel, NotificationManager.IMPORTANCE_DEFAULT))
  val launch = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  manager.notify(id, 1, Notification.Builder(context, UPCOMING_CHANNEL).setSmallIcon(io.github.kannoncoding.wealthward.R.drawable.ic_notification_dollar).setContentTitle(title).setContentText(detail).setStyle(Notification.BigTextStyle().bigText(detail)).setContentIntent(launch).setAutoCancel(true).build())
  true
 } catch (_: SecurityException) { false }
}
class ReminderReceiver : BroadcastReceiver() {
 override fun onReceive(context: Context, intent: Intent) { if(intent.action=="wealthward.reminder") LocalReminders(context).deliver(intent.getStringExtra("id") ?: return, intent.getStringExtra("due") ?: return, intent.getIntExtra("lead", -1)) }
}
class ReminderBootReceiver : BroadcastReceiver() {
 override fun onReceive(context: Context, intent: Intent) { if(intent.action==Intent.ACTION_BOOT_COMPLETED || intent.action==Intent.ACTION_MY_PACKAGE_REPLACED) LocalReminders(context).reconcile() }
}
