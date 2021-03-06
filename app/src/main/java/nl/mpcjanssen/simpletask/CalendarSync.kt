/**

 * Copyright (c) 2015 Vojtech Kral

 * LICENSE:

 * Simpletask is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation, either version 2 of the License, or (at your option) any
 * later version.

 * Simpletask is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more
 * details.

 * You should have received a copy of the GNU General Public License along with Sinpletask.  If not, see
 * //www.gnu.org/licenses/>.

 * @author Vojtech Kral
 * *
 * @license http://www.gnu.org/licenses/gpl.html
 * *
 * @copyright 2015 Vojtech Kral
 */

package nl.mpcjanssen.simpletask

import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.ContentResolver
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.*
import android.support.v4.content.ContextCompat
import hirondelle.date4j.DateTime
import nl.mpcjanssen.simpletask.task.TToken
import nl.mpcjanssen.simpletask.task.Task
import nl.mpcjanssen.simpletask.task.TodoList
import nl.mpcjanssen.simpletask.util.Config
import nl.mpcjanssen.simpletask.util.toDateTime
import java.util.*
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit


object CalendarSync {
    private val log: Logger
    private val UTC = TimeZone.getTimeZone("UTC")

    private val ACCOUNT_NAME = "Simpletask Calendar"
    private val ACCOUNT_TYPE = CalendarContract.ACCOUNT_TYPE_LOCAL
    private val CAL_URI = Calendars.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true").appendQueryParameter(Calendars.ACCOUNT_NAME, ACCOUNT_NAME).appendQueryParameter(Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE).build()
    private val CAL_NAME = "simpletask_reminders_v34SsjC7mwK9WSVI"
    private val CAL_COLOR = Color.BLUE       // Chosen arbitrarily...
    private val EVT_DURATION_DAY = 24 * 60 * 60 * 1000  // ie. 24 hours
    private val TASK_TOKENS = TToken.ALL and
            (TToken.COMPLETED or
                    TToken.COMPLETED_DATE or
                    TToken.CREATION_DATE or
                    TToken.PRIO or
                    TToken.THRESHOLD_DATE or
                    TToken.DUE_DATE or
                    TToken.HIDDEN or
                    TToken.RECURRENCE).inv()

    private val SYNC_DELAY_MS = 1000
    private val TAG = "CalendarSync"


    val SYNC_TYPE_DUES = 1
    val SYNC_TYPE_THRESHOLDS = 2

    private class SyncRunnable : Runnable {
        override fun run() {
            try {
                sync()
            } catch (e: Exception) {
                log.error(TAG, "STPE exception", e)
            }

        }
    }

    private val m_sync_runnable: SyncRunnable
    private val m_cr: ContentResolver
    private var m_rem_margin = 1440
    private var m_rem_time = DateTime.forTimeOnly(12, 0, 0, 0)
    private val m_stpe: ScheduledThreadPoolExecutor

    @SuppressLint("Recycle")
    private fun findCalendar(): Long {
        val projection = arrayOf(Calendars._ID, Calendars.NAME)
        val selection = Calendars.NAME + " = ?"
        val args = arrayOf(CAL_NAME)
        /* Check for calendar permission */
        val permissionCheck = ContextCompat.checkSelfPermission(TodoApplication.app,
                Manifest.permission.WRITE_CALENDAR)

        if (permissionCheck == PackageManager.PERMISSION_DENIED) {
            if (Config.isSyncDues || Config.isSyncThresholds) {
                throw IllegalStateException("no calendar access")
            } else {
                return -1
            }
        }

        val cursor = m_cr.query(CAL_URI, projection, selection, args, null) ?: throw IllegalArgumentException("null cursor")
        if (cursor.count == 0) return -1
        cursor.moveToFirst()
        val ret = cursor.getLong(0)
        cursor.close()
        return ret
    }

    private fun addCalendar() {
        val cv = ContentValues()
        cv.apply {
            put(Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            put(Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
            put(Calendars.NAME, CAL_NAME)
            put(Calendars.CALENDAR_DISPLAY_NAME, TodoApplication.app.getString(R.string.calendar_disp_name))
            put(Calendars.CALENDAR_COLOR, CAL_COLOR)
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_READ)
            put(Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
        }
        m_cr.insert(CAL_URI, cv)
    }

    private fun removeCalendar() {
        log.debug(TAG, "Removing Simpletask calendar")
        val selection = Calendars.NAME + " = ?"
        val args = arrayOf(CAL_NAME)
        try {
            val ret = m_cr.delete(CAL_URI, selection, args)
            if (ret == 0)
                log.debug(TAG, "No calendar to remove")
            else if (ret == 1)
                log.debug(TAG, "Calendar removed")
            else
                log.error(TAG, "Unexpected return value while removing calendar: " + ret)
        } catch (e: Exception) {
            log.error(TAG, "Error while removing calendar", e)
        }
    }

    @TargetApi(16) private fun insertEvt(calID: Long, date: DateTime, title: String, description: String) {
        val values = ContentValues()

        val localZone = Calendar.getInstance().timeZone
        val dtstart = date.getMilliseconds(UTC)

        // Event:
        values.apply {
            put(Events.CALENDAR_ID, calID)
            put(Events.TITLE, title)
            put(Events.DTSTART, dtstart)
            put(Events.DTEND, dtstart + EVT_DURATION_DAY)  // Needs to be set to DTSTART +24h, otherwise reminders don't work
            put(Events.ALL_DAY, 1)
            put(Events.DESCRIPTION, description)
            put(Events.EVENT_TIMEZONE, UTC.id)
            put(Events.STATUS, Events.STATUS_CONFIRMED)
            put(Events.HAS_ATTENDEE_DATA, true)      // If this is not set, Calendar app is confused about Event.STATUS
            put(Events.CUSTOM_APP_PACKAGE, TodoApplication.app.packageName)
            put(Events.CUSTOM_APP_URI, Uri.withAppendedPath(Simpletask.URI_SEARCH, title).toString())
        }
        val uri = m_cr.insert(Events.CONTENT_URI, values)

        // Reminder:
        // Only create reminder if it's in the future, otherwise it would go off immediately
        // NOTE: DateTime.minus()/plus() only accept values >=0 and <10000 (goddamnit date4j!), hence the division.
        var remDate = date.minus(0, 0, 0, m_rem_margin / 60, m_rem_margin % 60, 0, 0, DateTime.DayOverflow.Spillover)
        remDate = remDate.plus(0, 0, 0, m_rem_time.hour, m_rem_time.minute, 0, 0, DateTime.DayOverflow.Spillover)
        if (remDate.isInTheFuture(localZone)) {
            val evtID = java.lang.Long.parseLong(uri.lastPathSegment)
            values.apply {
                clear()
                put(Reminders.EVENT_ID, evtID)
                put(Reminders.MINUTES, remDate.numSecondsFrom(date) / 60)
                put(Reminders.METHOD, Reminders.METHOD_ALERT)
            }
            m_cr.insert(Reminders.CONTENT_URI, values)
        }
    }

    @SuppressLint("NewApi")
    private fun insertEvts(calID: Long, tasks: List<Task>?) {
        if (tasks == null) {
            return
        }
        tasks.forEach {
            if (!it.isCompleted()) {

                var dt: DateTime?
                var text: String? = null

                // Check due date:
                if (Config.isSyncDues) {
                    dt = it.dueDate?.toDateTime()
                    if (dt != null) {
                        text = it.showParts(TASK_TOKENS)
                        insertEvt(calID, dt, text, TodoApplication.app.getString(R.string.calendar_sync_desc_due))
                    }
                }
                it.dueDate?.toDateTime()
                // Check threshold date:
                if (Config.isSyncThresholds) {
                    dt = it.thresholdDate?.toDateTime()
                    if (dt != null) {
                        if (text == null) text = it.showParts(TASK_TOKENS)
                        insertEvt(calID, dt, text, TodoApplication.app.getString(R.string.calendar_sync_desc_thre))
                    }
                }
            }
        }
    }

    private fun purgeEvts(calID: Long) {
        val selection = Events.CALENDAR_ID + " = ?"
        val args = arrayOf("")
        args[0] = calID.toString()
        m_cr.delete(Events.CONTENT_URI, selection, args)
    }

    private fun sync() {
        log.debug(TAG,"Syncing Simpletask calendar")
        try {
            var calID = findCalendar()

            if (!Config.isSyncThresholds && !Config.isSyncDues) {
                if (calID >= 0) {
                    log.debug(TAG, "Calendar sync not enabled")
                    removeCalendar()
                }
                return
            }

            if (calID < 0) {
                addCalendar()
                calID = findCalendar()   // Re-find the calendar, this is needed to verify it has been added
                if (calID < 0) {
                    // This happens when CM privacy guard disallows to write calendar (1)
                    // OR it allows to write calendar but disallows reading it (2).
                    // Either way, we cannot continue, but before bailing,
                    // try to remove Calendar in case we're here because of (2).
                    log.debug(TAG, "No access to Simpletask calendar")
                    removeCalendar()
                    throw IllegalStateException("Calendar nor added")
                }
            }

            val tl = TodoList
            val tasks = tl.todoItems

            setReminderDays(Config.reminderDays)
            setReminderTime(Config.reminderTime)

            log.debug(TAG, "Syncing due/threshold calendar reminders...")
            purgeEvts(calID)
            insertEvts(calID, tasks)
        } catch (e: Exception) {
            log.error(TAG, "Calendar error", e)
        }
    }

    fun updatedSyncTypes() {
        syncLater()
    }

    init {
        log = Logger
        m_sync_runnable = SyncRunnable()
        m_cr = TodoApplication.app.contentResolver
        m_stpe = ScheduledThreadPoolExecutor(1)
    }

    fun syncLater() {
        m_stpe.queue.clear()
        m_stpe.schedule(m_sync_runnable, SYNC_DELAY_MS.toLong(), TimeUnit.MILLISECONDS)
    }

    fun setReminderDays(days: Int) {
        m_rem_margin = days * 1440
    }

    fun setReminderTime(time: Int) {
        m_rem_time = DateTime.forTimeOnly(time / 60, time % 60, 0, 0)
    }

}
