/*
 * This file is part of Blokada.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright © 2021 Blocka AB. All rights reserved.
 *
 * @author Karol Gusak (karol@blocka.net)
 */

package ui

import android.app.IntentService
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_VIEW
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import model.BlokadaException
import service.AlertDialogService
import service.ContextService
import service.EnvironmentService
import service.LogService
import ui.utils.cause
import utils.Logger

enum class Command {
    OFF, ON, DNS, LOG, ACC, ESCAPE, TOAST, DOH
}

const val ACC_MANAGE = "manage_account"
const val OFF = "off"
const val ON = "on"

private typealias Param = String

class CommandActivity : AppCompatActivity() {

    private val log = Logger("Command")

    private lateinit var tunnelVM: TunnelViewModel
    private lateinit var settingsVM: SettingsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tunnelVM = ViewModelProvider(app()).get(TunnelViewModel::class.java)
        settingsVM = ViewModelProvider(app()).get(SettingsViewModel::class.java)

        interpretCommand(intent.data.toString())?.let {
            val (cmd, param) = it
            log.w("Received command: $cmd")
            try {
                execute(cmd, param)
                log.v("Command executed successfully")
            } catch (ex: Exception) {
                log.e("Could not execute command".cause(ex))
            }
        } ?: run {
            log.e("Received unknown command: ${intent.data}")
        }

        finish()
    }

    private fun execute(command: Command, param: Param?) {
        when (command) {
            Command.OFF -> tunnelVM.turnOff()
            Command.ON -> tunnelVM.turnOn()
            Command.LOG -> LogService.shareLog()
            Command.ACC -> {
                if (param == ACC_MANAGE) {
                    log.v("Starting account management screen")
                    val intent = Intent(this, MainActivity::class.java).also {
                        it.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        it.putExtra(MainActivity.ACTION, ACC_MANAGE)
                    }
                    startActivity(intent)
                } else throw BlokadaException("Unknown param for command ACC: $param, ignoring")
            }
            Command.ESCAPE -> {
                if (param == null) {
                    settingsVM.setEscaped(true)
                } else {
                    val versionCode = param.toInt()
                    if (EnvironmentService.getVersionCode() <= versionCode) {
                        settingsVM.setEscaped(true)
                    } else {
                        log.v("Ignoring escape command, too new version code")
                    }
                }
            }
            Command.TOAST -> {
                Toast.makeText(this, param, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun interpretCommand(input: String): Pair<Command, Param?>? {
        return when {
            input.startsWith("blocka://cmd/") -> {
                input.replace("blocka://cmd/", "")
                    .trimEnd('/')
                    .split("/")
                    .let {
                        try {
                            Command.valueOf(it[0].toUpperCase()) to it.getOrNull(1)
                        } catch (ex: Exception) { null }
                    }
            }
            // Legacy commands to be removed in the future
            input.startsWith("blocka://log") -> Command.LOG to null
            input.startsWith("blocka://acc") -> Command.ACC to ACC_MANAGE
            else -> null
        }
    }

    private fun ensureParam(param: Param?): Param {
        return param ?: throw BlokadaException("Required param not provided")
    }

}

class CommandService : IntentService("cmd") {

    override fun onHandleIntent(intent: Intent?) {
        intent?.let {
            val ctx = ContextService.requireContext()
            ctx.startActivity(Intent(ACTION_VIEW, it.data).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
        }
    }

}

fun getIntentForCommand(command: Command, param: Param? = null): Intent {
    val ctx = ContextService.requireContext()
    return Intent(ctx, CommandService::class.java).apply {
        if (param == null) {
            data = Uri.parse("blocka://cmd/${command.name}")
        } else {
            data = Uri.parse("blocka://cmd/${command.name}/$param")
        }
    }
}

fun getIntentForCommand(cmd: String): Intent {
    val ctx = ContextService.requireContext()
    return Intent(ctx, CommandService::class.java).apply {
        data = Uri.parse("blocka://cmd/$cmd")
    }
}

fun executeCommand(cmd: Command, param: Param? = null) {
    val ctx = ContextService.requireContext()
    val intent = getIntentForCommand(cmd, param)
    ctx.startService(intent)
}