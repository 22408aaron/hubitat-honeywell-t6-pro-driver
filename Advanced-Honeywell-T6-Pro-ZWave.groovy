import groovy.transform.Field

/**
 * Advanced Honeywell T6 Pro Z-Wave Thermostat
 * Hubitat Z-Wave driver for the Honeywell / Resideo T6 Pro (TH6320ZW2003 / TH6320ZW2007)
 *
 * Copyright 2026 Aaron F. Stone
 * Portions Copyright Bryan Copeland
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * -----------------------------------------------------------------------------
 * ATTRIBUTION / DERIVATION (required by Apache-2.0 section 4):
 * This driver is based on "Advanced Honeywell T6 Pro Thermostat" v1.2 by
 * Bryan Copeland (djdizzyd), also licensed under Apache-2.0:
 *   https://github.com/djdizzyd/hubitat  (Drivers/Honeywell/Advanced-Honeywell-T6-Pro.groovy)
 *
 * Modifications by Aaron F. Stone in this version:
 *   - home() / away() commands  -> toggle Home/Away (energy-save) mode via Z-Wave Basic Set
 *   - awayMode attribute        -> reflects current Home/Away state
 *   - setAwayHeatingSetpoint / setAwayCoolingSetpoint -> remotely program the Away temperatures
 *   - awayHeatingSetpoint / awayCoolingSetpoint attributes
 *   - probeSetpointTypes() + supported-setpoint-types report handling (confirms whether the
 *     device's Away temps use setpoint types 11/12 "Energy Save" or 13/14 "Away")
 *   - thermostatFanState attribute (fan running/idle visibility)
 *   - per-mode enable toggles -> choose which thermostat modes the device exposes
 *     (e.g. turn off heat/emergency heat/auto for a cool-only device)
 *   - per-fan-mode enable toggles -> choose which fan modes the device exposes
 *     (e.g. leave only Fan Auto on to lock the fan to Auto)
 *     [driver preferences can't do multi-select enums, so these are individual toggles]
 *   - seedSetpointAttributes() -> seeds a placeholder heating/cooling setpoint when the device
 *     doesn't report one (cool-only or heat-only setups), so the Hubitat dashboard Thermostat
 *     tile stops erroring with "missing attribute; heatingSetpoint"
 *   - programmed setpoint / temporary hold -> program a baseline setpoint in Hubitat (preference or
 *     setProgrammedHeatingSetpoint/setProgrammedCoolingSetpoint); a manual change at the thermostat is
 *     held for autoHoldMinutes then reverts to the programmed value. resumeSchedule reverts immediately.
 *   - clearer preference labels + descriptions on all custom fields
 *   - bug fixes: CMD_CLASS_VERS 043 (octal 35) corrected to 0x43 and 0x20 Basic added;
 *     several device.currentValue() calls given their missing attribute-name argument;
 *     removed a stray configurationGet(parameterNumber: 52) (no such parameter on this device)
 *   - renamed driver to "Advanced Honeywell T6 Pro Z-Wave Thermostat"; set importUrl for updates
 *   - namespace changed to 22408aaron (matches the GitHub repo owner)
 * -----------------------------------------------------------------------------
 *
 * v2.8
 */

metadata {
    definition (name: "Advanced Honeywell T6 Pro Z-Wave Thermostat", namespace: "22408aaron", author: "Aaron F. Stone", importUrl: "https://raw.githubusercontent.com/22408aaron/hubitat-honeywell-t6-pro-driver/main/Advanced-Honeywell-T6-Pro-ZWave.groovy") {

        capability "Actuator"
        capability "Battery"
        capability "Configuration"
        capability "Refresh"
        capability "Sensor"
        capability "TemperatureMeasurement"
        capability "Thermostat"
        capability "ThermostatMode"
        capability "ThermostatFanMode"
        capability "ThermostatSetpoint"
        capability "ThermostatCoolingSetpoint"
        capability "ThermostatHeatingSetpoint"
        capability "ThermostatOperatingState"
        capability "RelativeHumidityMeasurement"
        capability "PowerSource"

        attribute "currentSensorCal", "number"
        attribute "idleBrightness", "number"
        attribute "thermostatFanState", "enum", ["idle", "running"]
        // --- Away Edition additions ---
        attribute "awayMode", "enum", ["home", "away"]
        attribute "awayHeatingSetpoint", "number"
        attribute "awayCoolingSetpoint", "number"
        attribute "programmedHeatingSetpoint", "number"
        attribute "programmedCoolingSetpoint", "number"
        attribute "holdStatus", "enum", ["following schedule", "temporary hold"]

        command "SensorCal", [[name:"calibration",type:"ENUM", description:"Number of degrees to add/subtract from thermostat sensor", constraints:["-3", "-2", "-1", "0", "1", "2", "3"]]]
        command "IdleBrightness", [[name:"brightness",type:"ENUM", description:"Set idle brightness", constraints:["0", "1", "2", "3", "4", "5"]]]
        command "syncClock"
        // --- Away Edition additions ---
        command "home"
        command "away"
        command "setAwayHeatingSetpoint", [[name:"degrees",type:"NUMBER", description:"Away/energy-save heating setpoint"]]
        command "setAwayCoolingSetpoint", [[name:"degrees",type:"NUMBER", description:"Away/energy-save cooling setpoint"]]
        command "probeSetpointTypes"
        // --- Auto-setpoint (programmed baseline + temporary hold) ---
        command "setProgrammedHeatingSetpoint", [[name:"degrees",type:"NUMBER", description:"Set the programmed HEAT baseline. A change made at the thermostat reverts to this after the hold time."]]
        command "setProgrammedCoolingSetpoint", [[name:"degrees",type:"NUMBER", description:"Set the programmed COOL baseline. A change made at the thermostat reverts to this after the hold time."]]
        command "resumeSchedule"

        fingerprint  mfr:"0039", prod:"0011", deviceId:"0008", inClusters:"0x5E,0x85,0x86,0x59,0x31,0x80,0x81,0x70,0x5A,0x72,0x71,0x73,0x9F,0x44,0x45,0x40,0x42,0x43,0x6C,0x55", deviceJoinName: "Honeywell T6 PRO"

    }
    preferences {
        // ===== HVAC installer parameters (the 42 T6 ISU settings) =====
        // These are the thermostat's own equipment/comfort settings. Most people never change them.
        // (Hubitat drivers can't show section headers, so everything below is one long list.)
        configParams.each { input it.value.input }

        // ===== Away / energy-save temperatures =====
        input name: "awaySetpointType", type: "enum", title: "Away temp Z-Wave type",
                description: "Which Z-Wave setpoint pair the T6 uses for its Away (energy-save) temps. This T6 uses 11/12 - leave it there. If the Away temps stop responding, run the 'Probe Setpoint Types' command and read the log.",
                defaultValue: "11/12", options: ["11/12":"11/12 - Energy Save (correct for this T6)", "13/14":"13/14 - Away (not supported on this T6)"]

        // ===== Which MODES this device offers =====
        // Turn a mode OFF to hide it from dashboards and block it. Multi-select isn't possible in
        // Hubitat drivers, so each mode is its own switch. If you turn ALL of them off, all are shown.
        input name: "modeOff", type: "bool", title: "Offer 'Off' mode",
                description: "Turn modes OFF to hide/block them - e.g. turn Heat, Auto and Emergency Heat off for a cooling-only unit. (If every mode is off, all modes are shown.)", defaultValue: true
        input name: "modeCool", type: "bool", title: "Offer 'Cool' mode", defaultValue: true
        input name: "modeHeat", type: "bool", title: "Offer 'Heat' mode", defaultValue: true
        input name: "modeAuto", type: "bool", title: "Offer 'Auto' mode", description: "Auto needs both Heat and Cool available.", defaultValue: true
        input name: "modeEmergencyHeat", type: "bool", title: "Offer 'Emergency Heat' mode", defaultValue: true

        // ===== Which FAN modes this device offers =====
        input name: "fanModeAuto", type: "bool", title: "Offer fan 'Auto'",
                description: "Turn fan modes off to limit fan control. Leave only 'Auto' on to effectively lock the fan to Auto. (If every fan mode is off, all are shown.)", defaultValue: true
        input name: "fanModeOn", type: "bool", title: "Offer fan 'On'", defaultValue: true
        input name: "fanModeCirculate", type: "bool", title: "Offer fan 'Circulate'", defaultValue: true

        // ===== Programmed setpoint + temporary hold =====
        input name: "autoEnable", type: "bool", title: "Enable programmed setpoint + temporary hold",
                description: "When ON, Hubitat holds the thermostat at the programmed temps below. A change made AT the thermostat is kept only temporarily, then reverts to your programmed temp.", defaultValue: false
        input name: "autoHeatingSetpoint", type: "number", title: "Programmed HEAT setpoint (degrees)",
                description: "The temperature held in Heat mode (and the low limit in Auto mode). Leave blank if this unit never heats."
        input name: "autoCoolingSetpoint", type: "number", title: "Programmed COOL setpoint (degrees)",
                description: "The temperature held in Cool mode (and the high limit in Auto mode). Leave blank if this unit never cools."
        input name: "autoHoldMinutes", type: "number", title: "Temporary-hold length (minutes)",
                description: "How long a change made at the thermostat is kept before it reverts to your programmed temp. 0 = revert almost immediately.", defaultValue: 120, range: "0..1440"

        // ===== Driver logging =====
        input "logEnable", "bool", title: "Enable debug logging", description: "Extra detail in the Logs page; turns itself off after 30 minutes.", defaultValue: false
    }

}

@Field static Map CMD_CLASS_VERS=[0x20:1, 0x71:3, 0x7A:2, 0x81:1, 0x73:1, 0x2B:1, 0x2C:1, 0x85:2, 0x72:1, 0x86:2, 0x8F:1, 0x31:5, 0x70:1, 0x80:1, 0x45:1, 0x44:3, 0x43:2, 0x42:1, 0x40:2, 0x5A:1, 0x59:1, 0x5E:2]
@Field static Map THERMOSTAT_OPERATING_STATE=[0x00:"idle",0x01:"heating",0x02:"cooling",0x03:"fan only",0x04:"pending heat",0x05:"pending cool",0x06:"vent economizer"]
@Field static Map THERMOSTAT_MODE=[0x00:"off",0x01:"heat",0x02:"cool",0x03:"auto",0x04:"emergency heat"]
@Field static Map SET_THERMOSTAT_MODE=["off":0x00,"heat":0x01,"cool":0x02,"auto":0x03,"emergency heat":0x04]
@Field static Map THERMOSTAT_FAN_MODE=[0x00:"auto",0x01:"on",0x02:"auto",0x03:"on",0x04:"auto",0x05:"on",0x06:"circulate",0x07:"circulate"]
@Field static Map SET_THERMOSTAT_FAN_MODE=["auto":0x00,"on":0x01,"circulate":0x06]
@Field static Map THERMOSTAT_FAN_STATE=[0x00:"idle", 0x01:"running", 0x02:"running high",0x03:"running medium",0x04:"circulation mode",0x05:"humidity circulation mode",0x06:"right - left circulation mode",0x07:"quiet circulation mode"]
@Field static List<String> supportedThermostatFanModes=["on","auto","circulate"]
@Field static List<String> supportedThermostatModes=["auto", "off", "heat", "emergency heat", "cool"]
@Field static Map ZWAVE_NOTIFICATION_TYPES=[0:"Reserverd", 1:"Smoke", 2:"CO", 3:"CO2", 4:"Heat", 5:"Water", 6:"Access Control", 7:"Home Security", 8:"Power Management", 9:"System", 10:"Emergency", 11:"Clock", 12:"First"]
// Z-Wave Thermostat Setpoint types (for reference / probe decoding)
@Field static Map SETPOINT_TYPE_NAMES=[1:"Heating", 2:"Cooling", 7:"Furnace", 8:"Dry Air", 9:"Moist Air", 10:"Auto Changeover", 11:"Energy Save Heating", 12:"Energy Save Cooling", 13:"Away Heating", 14:"Away Cooling", 15:"Full Power"]
@Field static Map configParams = [
        1: [input: [name: "configParam1", type: "enum", title: "Schedule Type", description: "", defaultValue: 2, options: [0:"No schedule/Occupacy based schedule",1:"Every day the same",2:"5-2 Schedule",3:"5-1-1 Schedule",4:"Every day individual"]], parameterSize: 1],
        2: [input: [name: "configParam2", type: "enum", title: "Temperature Scale", description:"", defaultValue: 0, options: [0:"Fahrenheit", 1:"Celsius"]], parameterSize: 1],
        3: [input: [name: "configParam3", type: "enum", title: "Outdoor Temperature", description:"", defaultValue: 0, options: [0:"No", 1:"Wired"]], parameterSize: 1],
        4: [input: [name: "configParam4", type: "enum", title: "Equipment Type", defaultValue: 2, options: [0:"None", 1:"Standard Gas",2:"High Efficiency Gas",3:"Oil",4:"Electric",5:"Fan Coil",6:"Air to Air Heat Pump",7:"Geothermal Heat Pump",8:"Hot Water",9:"Steam"]], parameterSize: 1],
        5: [input: [name: "configParam5", type: "enum", title: "Reversing Valve", defaultValue: 0, options: [0:"O/B on Cool", 1:"O/B on Heat"]], parameterSize:1],
        6: [input: [name: "configParam6", type: "enum", title: "Stages", defaultValue: 1, options: [0:"0", 1:"1",2:"2"]], parameterSize:1],
        7: [input: [name: "configParam7", type: "enum", title: "Heat Stages Aux/E stages", defaultValue: 1, options: [0:"0", 1:"1",2:"2"]], parameterSize:1],
        8: [input: [name: "configParam8", type: "enum", title: "Aux/E Control", defaultValue: 0, options:[0:"Both Aux and E", 1:"Either Aux/E"]], parameterSize: 1],
        9: [input: [name: "configParam9", type: "enum", title: "Aux Heat Type", defaultValue: 0, options:[0:"Electric", 1:"Gas/Oil"]], parameterSize: 1],
        10: [input: [name: "configParam10", type: "enum", title: "EM Heat Type", defaultValue: 0, options:[0:"Electric", 1:"Gas/Oil"]], parameterSize: 1],
        11: [input: [name: "configParam11", type: "enum", title: "Fossil Kit Control", defaultValue: 0, options:[0:"Thermostat",1:"External"]], parameterSize: 1],
        12: [input: [name: "configParam12", type: "enum", title: "Auto Changeover", defaultValue: 0, options:[0:"Off",1:"On"]], parameterSize: 1],
        13: [input: [name: "configParam13", type: "enum", title: "Auto Differential", defaultValue: 0, options:[0:"0°F",1:"1°F",2:"2°F",3:"3°F",4:"4°F",5:"5°F"]], parameterSize: 1],
        14: [input: [name: "configParam14", type: "enum", title: "High Cool Stage Finish", defaultValue: 0, options:[0:"No",1:"Yes"]], parameterSize: 1],
        15: [input: [name: "configParam15", type: "enum", title: "High Heat Stage Finish", defaultValue: 0, options:[0:"No",1:"Yes"]], parameterSize: 1],
        16: [input: [name: "configParam16", type: "enum", title: "Aux Heat Droop", defaultValue: 0, options:[0:"Comfort",2:"2°F",3:"3°F",4:"4°F",5:"5°F",6:"6°F",7:"7°F",8:"8°F",9:"9°F",10:"10°F",11:"11°F",12:"12°F",13:"13°F",14:"14°F",15:"15°F"]], parameterSize: 1],
        17: [input: [name: "configParam17", type: "enum", title: "Up Stage Timer Aux Heat", defaultValue: 0, options:[0:"Off",1:"30 minutes",2:"45 minutes",3:"60 minutes",4:"75 minutes",5:"90 minutes",6:"2 hours",7:"3 hours",8:"4 hours",9:"5 hours",10:"6 hours",11:"8 hours",12:"10 hours",13:"12 hours",14:"14 hours",15:"16 hours"]], parameterSize: 1],
        18: [input: [name: "configParam18", type: "enum", title: "Balance Point (Compressor Lockout)", defaultValue: 65, options:[0:"Off",5:"5°F",10:"10°F",15:"15°F",20:"20°F",25:"25°F",30:"30°F",35:"35°F",40:"40°F",45:"45°F",50:"50°F",55:"55°F",60:"60°F",65:"65°F"]], parameterSize: 1],
        19: [input: [name: "configParam19", type: "enum", title: "Aux Heat Lock Out (Aux Heat Outdoor Lockout)", defaultValue: 0, options:[0:"Off",5:"5°F",10:"10°F",15:"15°F",20:"20°F",25:"25°F",30:"30°F",35:"35°F",40:"40°F",45:"45°F",50:"50°F",55:"55°F",60:"60°F",65:"65°F"]], parameterSize: 1],
        20: [input: [name: "configParam20", type: "enum", title: "Cool 1 CPH (Cooling cycle rate stage 1)", defaultValue: 3, options:[1:"1",2:"2",3:"3",4:"4",5:"5",6:"6"]], parameterSize: 1],
        21: [input: [name: "configParam21", type: "enum", title: "Cool 2 CPH (Cooling cycle rate stage 2)", defaultValue: 3, options:[1:"1",2:"2",3:"3",4:"4",5:"5",6:"6"]], parameterSize: 1],
        22: [input: [name: "configParam22", type: "enum", title: "Heat 1 CPH (Heating cycle rate stage 1)", defaultValue: 3, options:[1:"1",2:"2",3:"3",4:"4",5:"5",6:"6",7:"7",8:"8",9:"9",10:"10",11:"11",12:"12"]], parameterSize: 1],
        23: [input: [name: "configParam23", type: "enum", title: "Heat 2 CPH (Heating cycle rate stage 2)", defaultValue: 3, options:[1:"1",2:"2",3:"3",4:"4",5:"5",6:"6",7:"7",8:"8",9:"9",10:"10",11:"11",12:"12"]], parameterSize: 1],
        24: [input: [name: "configParam24", type: "enum", title: "Aux Heat CPH (Heating cycle rate Auxiliary Heat)", defaultValue: 9, options:[1:"1",2:"2",3:"3",4:"4",5:"5",6:"6",7:"7",8:"8",9:"9",10:"10",11:"11",12:"12"]], parameterSize: 1],
        25: [input: [name: "configParam25", type: "enum", title: "EM Heat CPH (Heating cycle rate Emergency Heat)", defaultValue: 9, options:[1:"1",2:"2",3:"3",4:"4",5:"5",6:"6",7:"7",8:"8",9:"9",10:"10",11:"11",12:"12"]], parameterSize: 1],
        26: [input: [name: "configParam26", type: "enum", title: "Compressor Protection", defaultValue: 5, options:[0:"Off",1:"1 minutes",2:"2 minutes",3:"3 minutes",4:"4 minutes",5:"5 minutes"]], parameterSize: 1],
        27: [input: [name: "configParam27", type: "enum", title: "Adaptive Intelligent Recovery", defaultValue: 1, options:[0:"Off",1:"On"]], parameterSize: 1],
        28: [input: [name: "configParam28", type: "number", title: "Minimum Cool Temperature", description: "degrees fahrenheit", defaultValue: 50, range: "50..99"], parameterSize: 1],
        29: [input: [name: "configParam29", type: "number", title: "Maximum Heat Temperature", description: "degrees fahrenheit", defaultValue: 90, range: "40..90"], parameterSize: 1],
        30: [input: [name: "configParam30", type: "enum", title: "Air Filters", defaultValue: 0, options:[0:"0",1:"1",2:"2"]], parameterSize: 1],
        31: [input: [name: "configParam31", type: "enum", title: "Air Filter 1 Reminder", defaultValue: 0, options:[0:"Off",1:"10 run time days",2:"20 run time days",3:"30 run time days",4:"45 run time days",5:"60 run time days",6:"90 run time days",7:"120 run time days",8:"150 run time days",9:"30 days",10:"45 days",11:"60 days",12:"75 days",13:"3 months",14:"4 months",15:"5 months",16:"6 months",17:"9 months",18:"12 months",19:"15 months"]], parameterSize: 1],
        32: [input: [name: "configParam32", type: "enum", title: "Air Filter 2 Reminder", defaultValue: 0, options:[0:"Off",1:"10 run time days",2:"20 run time days",3:"30 run time days",4:"45 run time days",5:"60 run time days",6:"90 run time days",7:"120 run time days",8:"150 run time days",9:"30 days",10:"45 days",11:"60 days",12:"75 days",13:"3 months",14:"4 months",15:"5 months",16:"6 months",17:"9 months",18:"12 months",19:"15 months"]], parameterSize: 1],
        33: [input: [name: "configParam33", type: "enum", title: "Humidification Pad Reminder", defaultValue: 0, options:[0:"Off",1:"6 months",2:"12 months"]], parameterSize: 1],
        34: [input: [name: "configParam34", type: "enum", title: "Dehumidification Filter Reminder", defaultValue: 0, options:[0:"Off",1:"1 months",2:"2 months",3:"3 months",4:"4 months",5:"5 months",6:"6 months",7:"7 months",8:"8 months",9:"9 months",10:"10 months",11:"11 months",12:"12 months"]], parameterSize: 1],
        35: [input: [name: "configParam35", type: "enum", title: "Ventilation Filter Reminder", defaultValue: 0, options:[0:"Off",3:"3 months",6:"6 months",9:"9 months",12:"12 months"]], parameterSize: 1],
        36: [input: [name: "configParam36", type: "enum", title: "UV Devices", defaultValue: 0, options:[0:"0",1:"1",2:"2"]], parameterSize: 1],
        37: [input: [name: "configParam37", type: "enum", title: "UV Bulb 1 Reminder", defaultValue: 0, options:[0:"Off",6:"6 months",12:"12 months",24:"24 months"]], parameterSize: 1],
        38: [input: [name: "configParam38", type: "enum", title: "UV Bulb 2 Reminder", defaultValue: 0, options:[0:"Off",6:"6 months",12:"12 months",24:"24 months"]], parameterSize: 1],
        39: [input: [name: "configParam39", type: "enum", title: "Idle Brightness", defaultValue: 0, options:[0:"0",1:"1",2:"2",3:"3",4:"4",5:"5"]], parameterSize: 1],
        40: [input: [name: "configParam40", type: "enum", title: "Clock Format", defaultValue: 0, options: [0:"12 hour", 1:"24 hour"]], parameterSize:1],
        41: [input: [name: "configParam41", type: "enum", title: "Daylight Savings", defaultValue:1, options:[0:"Off",1:"On"]], parameterSize: 1],
        42: [input: [name: "configParam42", type: "enum", title: "Temperature Offset", defaultValue: 0, options:[(-3):"-3°F",(-2):"-2°F",(-1):"-1°F",0:"Off",1:"+1°F",2:"+2°F",3:"+3°F"]], parameterSize: 1]
]

void logsOff(){
    log.warn "debug logging disabled..."
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}

void configure() {
    if (!state.initialized) initializeVars()
    runIn(10, "syncClock")
    runIn(5, "pollDeviceData")
    runEvery3Hours("syncClock")
}

void initializeVars() {
    // first run only
    updateSupportedModes()
    updateSupportedFanModes()
    state.initialized=true
    runIn(15, refresh)
}

// Reads a bool preference, treating "not yet set" as the given default.
private boolean prefBool(String name, boolean dflt) {
    def v = settings?."${name}"
    return (v == null) ? dflt : (v == true || v == "true")
}

// Returns the modes this device exposes, per the per-mode toggles (all if every toggle is off).
private List<String> getEnabledModes() {
    List<String> modes = []
    if (prefBool("modeOff", true)) modes << "off"
    if (prefBool("modeCool", true)) modes << "cool"
    if (prefBool("modeHeat", true)) modes << "heat"
    if (prefBool("modeAuto", true)) modes << "auto"
    if (prefBool("modeEmergencyHeat", true)) modes << "emergency heat"
    return modes ?: supportedThermostatModes
}

// Returns the fan modes this device exposes, per the per-fan-mode toggles (all if every toggle is off).
private List<String> getEnabledFanModes() {
    List<String> modes = []
    if (prefBool("fanModeAuto", true)) modes << "auto"
    if (prefBool("fanModeOn", true)) modes << "on"
    if (prefBool("fanModeCirculate", true)) modes << "circulate"
    return modes ?: supportedThermostatFanModes
}

// Publish the supportedThermostatModes attribute that dashboards/apps read.
void updateSupportedModes() {
    List<String> modes = getEnabledModes()
    sendEvent(name:"supportedThermostatModes", value: modes.toString().replaceAll(/"/,""), isStateChange:true)
    if (logEnable) log.debug "supportedThermostatModes set to ${modes}"
}

// Publish the supportedThermostatFanModes attribute that dashboards/apps read.
void updateSupportedFanModes() {
    List<String> modes = getEnabledFanModes()
    sendEvent(name:"supportedThermostatFanModes", value: modes.toString().replaceAll(/"/,""), isStateChange:true)
    if (logEnable) log.debug "supportedThermostatFanModes set to ${modes}"
}

void installed() {
    if (logEnable) log.debug "installed()..."
    initializeVars()
}

void updated() {
    log.info "updated..."
    log.warn "debug logging is: ${logEnable == true}"
    unschedule()
    if (logEnable) runIn(1800,logsOff)
    runConfigs()
    updateSupportedModes()
    updateSupportedFanModes()
    runIn(8, "seedSetpointAttributes")
    state.holdActive = false
    sendEvent(name: "holdStatus", value: "following schedule")
    if (autoEnable) {
        def h = getAutoHeat(); if (h != null) sendEvent(name: "programmedHeatingSetpoint", value: h, unit: getTemperatureScale())
        def c = getAutoCool(); if (c != null) sendEvent(name: "programmedCoolingSetpoint", value: c, unit: getTemperatureScale())
        runIn(12, "applyAutoSetpoints")
        runEvery15Minutes("checkHold")
    }
    runEvery3Hours("syncClock")
}

void SensorCal(value) {
    if (logEnable) log.debug "SensorCal($value)"
    List<hubitat.zwave.Command> cmds=[]
    cmds.addAll(configCmd(42,1,value))
    sendToDevice(cmds)
}

void IdleBrightness(value) {
    if (logEnable) log.debug "IdleBrightness($value)"
    List<hubitat.zwave.Command> cmds=[]
    cmds.addAll(configCmd(39,1,value))
    sendToDevice(cmds)
}

void zwaveEvent(hubitat.zwave.commands.notificationv3.NotificationReport cmd) {
    Map evt = [isStateChange:false]
    log.info "Notification: " + ZWAVE_NOTIFICATION_TYPES[cmd.notificationType]
    if (cmd.notificationType==8) {
        // power management
        switch (cmd.event) {
            case 0:
                // idle
                break
            case 1:
                // Power has been applied
                log.info "${device.displayName} Power has been applied"
                break
            case 2:
                // AC mains disconnected
                evt.name="powerSource"
                evt.isStateChange=true
                evt.value="battery"
                evt.descriptionText="${device.displayName} AC mains disconnected"
                break
            case 3:
                // AC mains re-connected
                evt.name="powerSource"
                evt.isStateChange=true
                evt.value="mains"
                evt.descriptionText="${device.displayName} AC mains re-connected"
                break
            case 4:
                // surge detected
                log.warn "${device.displayName} surge detected"
                break
            case 5:
                // voltage drop / drift
                break
            case 6:
                // Over-current detected
                break
            case 7:
                // Over-voltage detected
                break
            case 8:
                // over-load detected
                break
            case 9:
                // load error
                break
            case 10:
                // replace battery soon
                break
            case 11:
                // replace battery now
                break
            case 12:
                // battery is charging
                log.info "${device.displayName} Battery is charging"
                break
            case 13:
                // battery is fully charged
                break
            case 14:
                // charge battery soon
                break
            case 15:
                // charge battery now
                break
            case 16:
                // backup battery is low
                break
            case 17:
                // battery fluid is low
                break
            case 18:
                // backup battery disconnected
                break
            case 254:
                // unknown event / state
                break
        }
    }
    if (evt.isStateChange) {
        if (txtEnable) log.info evt.descriptionText
        eventProcess(evt)
    }
}

void runConfigs() {
    List<hubitat.zwave.Command> cmds=[]
    configParams.each { param, data ->
        if (settings[data.input.name]) {
            cmds.addAll(configCmd(param, data.parameterSize, settings[data.input.name]))
        }
    }
    sendToDevice(cmds)
}

List<hubitat.zwave.Command> pollConfigs() {
    List<hubitat.zwave.Command> cmds=[]
    configParams.each { param, data ->
        if (settings[data.input.name]) {
            cmds.add(zwave.configurationV1.configurationGet(parameterNumber: param.toInteger()))
        }
    }
    return cmds
}

List<hubitat.zwave.Command> configCmd(parameterNumber, size, scaledConfigurationValue) {
    if (logEnable) log.debug "ParameterNumber: ${parameterNumber}, Size: ${size}, Value: ${scaledConfigurationValue}"
    List<hubitat.zwave.Command> cmds = []
    int intval=scaledConfigurationValue.toInteger()
    if (intval<0) intval=256 + intval
    cmds.add(zwave.configurationV1.configurationSet(parameterNumber: parameterNumber.toInteger(), size: size.toInteger(), configurationValue: [(intval & 0xFF)]))
    cmds.add(zwave.configurationV1.configurationGet(parameterNumber: parameterNumber.toInteger()))
    return cmds
}

void zwaveEvent(hubitat.zwave.commands.configurationv1.ConfigurationReport cmd) {
    int scaledValue
    cmd.configurationValue.reverse().eachWithIndex { v, index -> scaledValue=scaledValue | v << (8*index) }
    if(configParams[cmd.parameterNumber.toInteger()]) {
        Map configParam=configParams[cmd.parameterNumber.toInteger()]
        if (scaledValue > 127) scaledValue = scaledValue - 256
        device.updateSetting(configParam.input.name, [value: "${scaledValue}", type: configParam.input.type])
        if (cmd.parameterNumber==42) {
            eventProcess(name: "currentSensorCal", value: scaledValue)
        }
        if (cmd.parameterNumber==39) {
            eventProcess(name: "idleBrightness", value: scaledValue)
        }
    }
}

void eventProcess(Map evt) {
    if (device.currentValue(evt.name).toString() != evt.value.toString()) {
        evt.isStateChange=true
        sendEvent(evt)
    }
}

void pollDeviceData() {
    List<hubitat.zwave.Command> cmds = []
    cmds.addAll(processAssociations())
    cmds.add(zwave.manufacturerSpecificV2.deviceSpecificGet(deviceIdType: 1))
    cmds.add(zwave.versionV2.versionGet())
    cmds.add(zwave.thermostatSetpointV2.thermostatSetpointSupportedGet())
    cmds.addAll(pollConfigs())
    sendToDevice(cmds)
}

void refresh() {
    List<hubitat.zwave.Command> cmds=[]
    cmds.add(zwave.batteryV1.batteryGet())
    cmds.add(zwave.sensorMultilevelV5.sensorMultilevelGet(sensorType: 1, scale: configParam2==0?0:1))
    cmds.add(zwave.sensorMultilevelV5.sensorMultilevelGet(sensorType: 5, scale: 0))
    cmds.add(zwave.thermostatFanModeV3.thermostatFanModeGet())
    cmds.add(zwave.thermostatFanStateV1.thermostatFanStateGet())
    cmds.add(zwave.thermostatModeV2.thermostatModeGet())
    cmds.add(zwave.thermostatOperatingStateV1.thermostatOperatingStateGet())
    cmds.add(zwave.thermostatSetpointV2.thermostatSetpointGet(setpointType: 1))
    cmds.add(zwave.thermostatSetpointV2.thermostatSetpointGet(setpointType: 2))
    // --- Away Edition: read back the Away/energy-save setpoints for the configured type pair ---
    List awayTypes = getAwaySetpointTypes()
    cmds.add(zwave.thermostatSetpointV2.thermostatSetpointGet(setpointType: awayTypes[0]))
    cmds.add(zwave.thermostatSetpointV2.thermostatSetpointGet(setpointType: awayTypes[1]))
    sendToDevice(cmds)
    runIn(8, "seedSetpointAttributes")
    runIn(10, "syncClock")
}

// The Hubitat dashboard Thermostat tile requires BOTH heatingSetpoint and coolingSetpoint to exist,
// even on a cool-only (or heat-only) system. A cool-only T6 doesn't report the unused side, so the tile
// errors with "missing attribute; heatingSetpoint". Seed a placeholder for whichever side is missing.
// Runs after refresh so any real value the device reports takes precedence.
void seedSetpointAttributes() {
    String unit = getTemperatureScale()
    def heat = device.currentValue("heatingSetpoint")
    def cool = device.currentValue("coolingSetpoint")
    if (heat == null) {
        def v = (cool != null) ? cool : (unit == "F" ? 62 : 17)
        if (logEnable) log.debug "Seeding placeholder heatingSetpoint=${v}${unit} (device did not report one)"
        sendEvent(name: "heatingSetpoint", value: v, unit: unit)
        heat = v
    }
    if (cool == null) {
        def v = (heat != null) ? heat : (unit == "F" ? 78 : 26)
        if (logEnable) log.debug "Seeding placeholder coolingSetpoint=${v}${unit} (device did not report one)"
        sendEvent(name: "coolingSetpoint", value: v, unit: unit)
        cool = v
    }
    if (device.currentValue("thermostatSetpoint") == null) {
        def v = cool ?: heat
        if (v != null) sendEvent(name: "thermostatSetpoint", value: v, unit: unit)
    }
}

void syncClock() {
    Calendar currentDate = Calendar.getInstance()
    sendToDevice(zwave.clockV1.clockSet(hour: currentDate.get(Calendar.HOUR_OF_DAY), minute: currentDate.get(Calendar.MINUTE), weekday: currentDate.get(Calendar.DAY_OF_WEEK)))
}

void zwaveEvent(hubitat.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
    hubitat.zwave.Command encapsulatedCommand = cmd.encapsulatedCommand(CMD_CLASS_VERS)
    if (encapsulatedCommand) {
        zwaveEvent(encapsulatedCommand)
    }
}

void zwaveEvent(hubitat.zwave.commands.multicmdv1.MultiCmdEncap cmd) {
    if (logEnable) log.debug "Got multicmd: ${cmd}"
    cmd.encapsulatedCommands(CMD_CLASS_VERS).each { encapsulatedCommand ->
        zwaveEvent(encapsulatedCommand)
    }
}

void parse(String description) {
    if (logEnable) log.debug "parse:${description}"
    hubitat.zwave.Command cmd = zwave.parse(description, CMD_CLASS_VERS)
    if (cmd) {
        zwaveEvent(cmd)
    }
}

void zwaveEvent(hubitat.zwave.commands.supervisionv1.SupervisionGet cmd) {
    if (logEnable) log.debug "Supervision get: ${cmd}"
    hubitat.zwave.Command encapsulatedCommand = cmd.encapsulatedCommand(CMD_CLASS_VERS)
    if (encapsulatedCommand) {
        zwaveEvent(encapsulatedCommand)
    }
    sendToDevice(new hubitat.zwave.commands.supervisionv1.SupervisionReport(sessionID: cmd.sessionID, reserved: 0, moreStatusUpdates: false, status: 0xFF, duration: 0))
}

void zwaveEvent(hubitat.zwave.commands.manufacturerspecificv2.DeviceSpecificReport cmd) {
    if (logEnable) log.debug "Device Specific Report: ${cmd}"
    switch (cmd.deviceIdType) {
        case 1:
            // serial number
            def serialNumber=""
            if (cmd.deviceIdDataFormat==1) {
                cmd.deviceIdData.each { serialNumber += hubitat.helper.HexUtils.integerToHexString(it & 0xff,1).padLeft(2, '0')}
            } else {
                cmd.deviceIdData.each { serialNumber += (char) it }
            }
            device.updateDataValue("serialNumber", serialNumber)
            break
    }
}

void zwaveEvent(hubitat.zwave.commands.versionv2.VersionReport cmd) {
    if (logEnable) log.debug "version2 report: ${cmd}"
    device.updateDataValue("firmwareVersion", "${cmd.firmware0Version}.${cmd.firmware0SubVersion}")
    device.updateDataValue("protocolVersion", "${cmd.zWaveProtocolVersion}.${cmd.zWaveProtocolSubVersion}")
    device.updateDataValue("hardwareVersion", "${cmd.hardwareVersion}")
}

void sendToDevice(List<hubitat.zwave.Command> cmds) {
    sendHubCommand(new hubitat.device.HubMultiAction(commands(cmds), hubitat.device.Protocol.ZWAVE))
}

void sendToDevice(hubitat.zwave.Command cmd) {
    sendHubCommand(new hubitat.device.HubAction(secureCommand(cmd), hubitat.device.Protocol.ZWAVE))
}

void sendToDevice(String cmd) {
    sendHubCommand(new hubitat.device.HubAction(secureCommand(cmd), hubitat.device.Protocol.ZWAVE))
}

List<String> commands(List<hubitat.zwave.Command> cmds, Long delay=200) {
    return delayBetween(cmds.collect{ secureCommand(it) }, delay)
}

String secureCommand(hubitat.zwave.Command cmd) {
    secureCommand(cmd.format())
}

String secureCommand(String cmd) {
    return zwaveSecureEncap(cmd)
}

void zwaveEvent(hubitat.zwave.Command cmd) {
    if (logEnable) log.debug "skip:${cmd}"
}

List<hubitat.zwave.Command> setDefaultAssociation() {
    List<hubitat.zwave.Command> cmds=[]
    cmds.add(zwave.associationV2.associationSet(groupingIdentifier: 1, nodeId: zwaveHubNodeId))
    cmds.add(zwave.associationV2.associationGet(groupingIdentifier: 1))
    return cmds
}

List<hubitat.zwave.Command> processAssociations(){
    List<hubitat.zwave.Command> cmds = []
    cmds.addAll(setDefaultAssociation())
    return cmds
}

void zwaveEvent(hubitat.zwave.commands.associationv2.AssociationReport cmd) {
    if (logEnable) log.debug "${device.label?device.label:device.name}: ${cmd}"
    List<String> temp = []
    if (cmd.nodeId != []) {
        cmd.nodeId.each {
            temp.add(it.toString().format( '%02x', it.toInteger() ).toUpperCase())
        }
    }
    updateDataValue("zwaveAssociationG${cmd.groupingIdentifier}", "$temp")
}

void zwaveEvent(hubitat.zwave.commands.associationv2.AssociationGroupingsReport cmd) {
    if (logEnable) log.debug "${device.label?device.label:device.name}: ${cmd}"
    log.info "${device.label?device.label:device.name}: Supported association groups: ${cmd.supportedGroupings}"
    state.associationGroups = cmd.supportedGroupings
}

void zwaveEvent(hubitat.zwave.commands.batteryv1.BatteryReport cmd) {
    if (logEnable) log.debug "got battery report: ${cmd.batteryLevel}"
    Map evt = [name: "battery", unit: "%"]
    if (cmd.batteryLevel == 0xFF) {
        evt.descriptionText = "${device.displayName} has a low battery"
        evt.value = "1"
    } else {
        evt.descriptionText = "${device.displayName} battery is ${cmd.batteryLevel}%"
        evt.value = "${cmd.batteryLevel}"
    }
    if (txtEnable) log.info evt.descriptionText
    eventProcess(evt)
}

void zwaveEvent(hubitat.zwave.commands.sensormultilevelv5.SensorMultilevelReport cmd) {
    if (cmd.sensorType.toInteger() == 1) {
        if (logEnable) log.debug "got temp: ${cmd.scaledSensorValue}"
        eventProcess(name: "temperature", value: cmd.scaledSensorValue, unit: cmd.scale == 1 ? "F" : "C")
    } else if (cmd.sensorType.toInteger() == 5) {
        if (logEnable) log.debug "got humidity: ${cmd.scaledSensorValue}"
        eventProcess(name: "humidity", value: Math.round(cmd.scaledSensorValue), unit: cmd.scale == 0 ? "%": "g/m³")
    }
}

void setpointCalc(String newmode, String unit, value) {
    String mode="cool"
    if (device.currentValue("thermostatMode")=="heat" || device.currentValue("thermostatMode")=="emergency heat") {
        state.lastMode="heat"
        mode="heat"
    } else if (device.currentValue("thermostatMode")=="cool") {
        state.lastMode="cool"
        mode="cool"
    } else if (device.currentValue("thermostatOperatingState")=="heating" || device.currentValue("thermostatOperatingState")=="pending heat") {
        state.lastMode="heat"
        mode="heat"
    } else if (device.currentValue("thermostatOperatingState")=="cooling" || device.currentValue("thermostatOperatingState")=="pending cool") {
        state.lastMode="cool"
        mode="cool"
    } else if (state.lastMode) {
        mode=state.lastMode
    }
    if (newmode==mode) {
        eventProcess(name: "thermostatSetpoint", value: Math.round(value), unit: unit, type: state.isDigital?"digital":"physical")
    }
}

void zwaveEvent(hubitat.zwave.commands.thermostatsetpointv2.ThermostatSetpointReport cmd) {
    if (logEnable) log.debug "Got thermostat setpoint report: ${cmd}"
    boolean physical = !state.isDigital
    String unit=cmd.scale == 1 ? "F" : "C"
    switch (cmd.setpointType.toInteger()) {
        case 1:
            eventProcess(name: "heatingSetpoint", value: Math.round(cmd.scaledValue), unit: unit, type: physical?"physical":"digital")
            setpointCalc("heat", unit, cmd.scaledValue)
            if (physical) handleManualSetpoint("heat", cmd.scaledValue)
            break
        case 2:
            eventProcess(name: "coolingSetpoint", value: Math.round(cmd.scaledValue), unit: unit, type: physical?"physical":"digital")
            setpointCalc("cool", unit, cmd.scaledValue)
            if (physical) handleManualSetpoint("cool", cmd.scaledValue)
            break
        // --- Away Edition: energy-save / away setpoint types ---
        case 11:   // Energy Save Heating
        case 13:   // Away Heating
            eventProcess(name: "awayHeatingSetpoint", value: Math.round(cmd.scaledValue), unit: unit)
            break
        case 12:   // Energy Save Cooling
        case 14:   // Away Cooling
            eventProcess(name: "awayCoolingSetpoint", value: Math.round(cmd.scaledValue), unit: unit)
            break
    }
    state.isDigital=false
}

// --- Away Edition: log which setpoint types the physical device actually supports ---
void zwaveEvent(hubitat.zwave.commands.thermostatsetpointv2.ThermostatSetpointSupportedReport cmd) {
    if (logEnable) log.debug "Got thermostat setpoint supported report: ${cmd}"
    List<Integer> supported = decodeSupportedSetpointTypes(cmd)
    List<String> named = supported.collect { "${it} (${SETPOINT_TYPE_NAMES[it] ?: 'unknown'})" }
    state.supportedSetpointTypes = supported
    log.info "${device.displayName} supports Z-Wave setpoint types: ${named.join(', ')}"
    if (supported.contains(11) || supported.contains(12)) {
        log.info "  -> Away temps appear to use types 11/12 (Energy Save). Set 'Away setpoint type' preference to 11/12."
    }
    if (supported.contains(13) || supported.contains(14)) {
        log.info "  -> Away temps appear to use types 13/14 (Away). Set 'Away setpoint type' preference to 13/14."
    }
}

// Decode the Thermostat Setpoint Supported bitmask into a list of supported type numbers.
List<Integer> decodeSupportedSetpointTypes(cmd) {
    List<Integer> types = []
    List<Short> mask = []
    try {
        if (cmd.hasProperty('bitMask') && cmd.bitMask != null) mask = cmd.bitMask
        else if (cmd.hasProperty('supportedSetpointTypes') && cmd.supportedSetpointTypes != null) mask = cmd.supportedSetpointTypes as List
    } catch (ignored) { }
    mask.eachWithIndex { b, byteIndex ->
        for (int bit = 0; bit < 8; bit++) {
            if ((b & (1 << bit)) != 0) {
                int type = byteIndex * 8 + bit
                if (type > 0) types.add(type)
            }
        }
    }
    return types
}

void probeSetpointTypes() {
    log.info "Probing supported setpoint types (watch the logs for the result)..."
    List<hubitat.zwave.Command> cmds=[]
    cmds.add(zwave.thermostatSetpointV2.thermostatSetpointSupportedGet())
    // Also directly query the candidate away types; the device only replies for types it supports.
    [11, 12, 13, 14].each { cmds.add(zwave.thermostatSetpointV2.thermostatSetpointGet(setpointType: it)) }
    sendToDevice(cmds)
}

void zwaveEvent(hubitat.zwave.commands.thermostatoperatingstatev1.ThermostatOperatingStateReport cmd) {
    if (logEnable) log.debug "Got thermostat operating state report: ${cmd}"
    String newstate=THERMOSTAT_OPERATING_STATE[cmd.operatingState.toInteger()]
    if (logEnable) log.debug "Translated state: " + newstate
    eventProcess(name: "thermostatOperatingState", value: newstate)
    if (newstate=="cooling") {
        state.lastMode="cool"
    } else if (newstate=="heating") {
        state.lastMode="heat"
    } else if (newstate=="pending heat") {
        state.lastMode="heat"
    } else if (newstate=="pending cool") {
        state.lastMode="cool"
    }
}

void zwaveEvent(hubitat.zwave.commands.thermostatfanstatev1.ThermostatFanStateReport cmd) {
    if (logEnable) log.debug "Got thermostat fan state report: ${cmd}"
    String newstate=THERMOSTAT_FAN_STATE[cmd.fanOperatingState.toInteger()]
    if (logEnable) log.debug "Translated fan state: " + newstate
    eventProcess(name: "thermostatFanState", value: (newstate=="idle" ? "idle" : "running"))
    if (newstate=="idle" && (device.currentValue("thermostatOperatingState")=="heating" || device.currentValue("thermostatOperatingState")=="cooling")) {
        sendToDevice(zwave.thermostatOperatingStateV1.thermostatOperatingStateGet())
    }
}

void zwaveEvent(hubitat.zwave.commands.thermostatfanmodev2.ThermostatFanModeReport cmd) {
    if (logEnable) log.debug "Got thermostat fan mode report: ${cmd}"
    String newmode=THERMOSTAT_FAN_MODE[cmd.fanMode.toInteger()]
    if (logEnable) log.debug "Translated fan mode: " + newmode
    eventProcess(name: "thermostatFanMode", value: newmode, type: state.isDigital?"digital":"physical")
    state.isDigital=false
}

void zwaveEvent(hubitat.zwave.commands.thermostatmodev2.ThermostatModeReport cmd) {
    if (logEnable) log.debug "Got thermostat mode report: ${cmd}"
    String newmode=THERMOSTAT_MODE[cmd.mode.toInteger()]
    if (logEnable) log.debug "Translated thermostat mode: " + newmode
    eventProcess(name: "thermostatMode", value: newmode, type: state.isDigital?"digital":"physical")
    state.isDigital=false
}

// --- Away Edition: Basic Set/Report carry the Home(0xFF)/Away(0x00) state ---
void zwaveEvent(hubitat.zwave.commands.basicv1.BasicSet cmd) {
    if (logEnable) log.debug "Got BasicSet: ${cmd.value}"
    updateAwayModeFromBasic(cmd.value.toInteger())
    // also refresh operating state, which may have changed with the home/away switch
    sendToDevice(zwave.thermostatOperatingStateV1.thermostatOperatingStateGet())
}

void zwaveEvent(hubitat.zwave.commands.basicv1.BasicReport cmd) {
    if (logEnable) log.debug "Got BasicReport: ${cmd.value}"
    updateAwayModeFromBasic(cmd.value.toInteger())
}

private void updateAwayModeFromBasic(int value) {
    // 0x00 = Energy saving (Away); 0x01-0x63 and 0xFF = Comfort (Home)
    String mode = (value == 0) ? "away" : "home"
    eventProcess(name: "awayMode", value: mode)
}

private void setSetpoint(setPointType, value) {
    List<hubitat.zwave.Command> cmds=[]
    cmds.add(zwave.thermostatSetpointV2.thermostatSetpointSet(setpointType: setPointType, scale: getTemperatureScale()=="F" ? 1:0 , precision: 0, scaledValue: value))
    cmds.add(zwave.thermostatSetpointV2.thermostatSetpointGet(setpointType: setPointType))
    state.isDigital=true
    sendToDevice(cmds)
}

void setHeatingSetpoint(degrees) {
    if (logEnable) log.debug "setHeatingSetpoint(${degrees}) called"
    setSetpoint(1,degrees)
    state.isDigital=true
}

void setCoolingSetpoint(degrees) {
    if (logEnable) log.debug "setCoolingSetpoint(${degrees}) called"
    setSetpoint(2,degrees)
    state.isDigital=true
}

// --- Away Edition: Home/Away toggle + programmable Away setpoints ---

// Returns [heatingType, coolingType] based on the awaySetpointType preference (default 11/12).
private List<Integer> getAwaySetpointTypes() {
    return (settings?.awaySetpointType == "13/14") ? [13, 14] : [11, 12]
}

void away() {
    if (logEnable) log.debug "away() called - sending Basic Set 0x00 (energy save)"
    sendToDevice([zwave.basicV1.basicSet(value: 0x00), zwave.basicV1.basicGet()])
    eventProcess(name: "awayMode", value: "away")
}

void home() {
    if (logEnable) log.debug "home() called - sending Basic Set 0xFF (comfort)"
    sendToDevice([zwave.basicV1.basicSet(value: 0xFF), zwave.basicV1.basicGet()])
    eventProcess(name: "awayMode", value: "home")
}

void setAwayHeatingSetpoint(degrees) {
    Integer type = getAwaySetpointTypes()[0]
    if (logEnable) log.debug "setAwayHeatingSetpoint(${degrees}) -> setpoint type ${type}"
    setSetpoint(type, degrees)
}

void setAwayCoolingSetpoint(degrees) {
    Integer type = getAwaySetpointTypes()[1]
    if (logEnable) log.debug "setAwayCoolingSetpoint(${degrees}) -> setpoint type ${type}"
    setSetpoint(type, degrees)
}

// ---------------------------------------------------------------------------
// Auto-setpoint: program a baseline in Hubitat; a manual change at the thermostat
// becomes a TEMPORARY HOLD that reverts to the programmed value after autoHoldMinutes.
// The programmed baseline is the autoHeatingSetpoint / autoCoolingSetpoint preference;
// setProgrammedHeatingSetpoint / setProgrammedCoolingSetpoint let a rule or dashboard change it live
// (call them on a time schedule to build a multi-period "schedule").
// ---------------------------------------------------------------------------

// Programmed baseline (BigDecimal) or null if that side isn't being enforced.
private BigDecimal getAutoHeat() {
    def v = settings?.autoHeatingSetpoint
    return (v != null && v != "") ? (v as BigDecimal) : null
}
private BigDecimal getAutoCool() {
    def v = settings?.autoCoolingSetpoint
    return (v != null && v != "") ? (v as BigDecimal) : null
}

// Write the programmed baseline(s) to the thermostat and clear any hold.
void applyAutoSetpoints() {
    if (!autoEnable) return
    BigDecimal h = getAutoHeat()
    BigDecimal c = getAutoCool()
    if (h != null) { setSetpoint(1, h); sendEvent(name: "programmedHeatingSetpoint", value: h, unit: getTemperatureScale()) }
    if (c != null) { setSetpoint(2, c); sendEvent(name: "programmedCoolingSetpoint", value: c, unit: getTemperatureScale()) }
    state.holdActive = false
    sendEvent(name: "holdStatus", value: "following schedule")
    if (logEnable) log.debug "Applied programmed setpoints (heat=${h}, cool=${c})"
}

// Cancel any temporary hold and go back to the programmed baseline now.
void resumeSchedule() {
    unschedule("resumeSchedule")
    applyAutoSetpoints()
}

// Backstop: periodically re-read the setpoints so a manual change is caught even if
// the thermostat's unsolicited report was missed. Reports flow through the normal handler.
void checkHold() {
    if (!autoEnable) return
    List<hubitat.zwave.Command> cmds = []
    if (getAutoHeat() != null) cmds.add(zwave.thermostatSetpointV2.thermostatSetpointGet(setpointType: 1))
    if (getAutoCool() != null) cmds.add(zwave.thermostatSetpointV2.thermostatSetpointGet(setpointType: 2))
    if (cmds) sendToDevice(cmds)
}

// Called when a PHYSICAL setpoint change is seen. Starts/keeps a temporary hold, or clears it.
private void handleManualSetpoint(String mode, value) {
    if (!autoEnable) return
    BigDecimal target = (mode == "heat") ? getAutoHeat() : getAutoCool()
    if (target == null) return
    boolean atTarget = (Math.round(value as BigDecimal) == Math.round(target))
    if (atTarget) {
        if (state.holdActive) {
            state.holdActive = false
            unschedule("resumeSchedule")
            sendEvent(name: "holdStatus", value: "following schedule")
        }
        return
    }
    if (state.holdActive) return   // already holding; let the running timer finish
    state.holdActive = true
    sendEvent(name: "holdStatus", value: "temporary hold")
    int mins = (settings?.autoHoldMinutes != null) ? (settings.autoHoldMinutes as Integer) : 120
    if (mins <= 0) {
        runIn(2, "resumeSchedule")
    } else {
        runIn(mins * 60, "resumeSchedule")
    }
    if (logEnable) log.debug "Manual ${mode} change to ${value}; temporary hold, reverting to ${target} in ${mins} min"
}

void setProgrammedHeatingSetpoint(degrees) {
    device.updateSetting("autoHeatingSetpoint", [value: degrees, type: "number"])
    sendEvent(name: "programmedHeatingSetpoint", value: degrees, unit: getTemperatureScale())
    if (logEnable) log.debug "setProgrammedHeatingSetpoint(${degrees})"
    if (autoEnable) applyAutoSetpoints()
}

void setProgrammedCoolingSetpoint(degrees) {
    device.updateSetting("autoCoolingSetpoint", [value: degrees, type: "number"])
    sendEvent(name: "programmedCoolingSetpoint", value: degrees, unit: getTemperatureScale())
    if (logEnable) log.debug "setProgrammedCoolingSetpoint(${degrees})"
    if (autoEnable) applyAutoSetpoints()
}

void setThermostatMode(mode) {
    if (logEnable) log.debug "setThermostatMode($mode)"
    if (!getEnabledModes().contains(mode)) {
        log.warn "Thermostat mode '${mode}' is disabled in this device's preferences; ignoring."
        return
    }
    List<hubitat.zwave.Command> cmds = []
    if (logEnable) log.debug "setting zwave thermostat mode ${SET_THERMOSTAT_MODE[mode]}"
    cmds.add(zwave.thermostatModeV2.thermostatModeSet(mode: SET_THERMOSTAT_MODE[mode]))
    cmds.add(zwave.thermostatModeV2.thermostatModeGet())
    state.isDigital=true
    sendToDevice(cmds)
}

void off() {
    state.isDigital=true
    setThermostatMode("off")
}

void on() {
    log.warn "Ambiguous use of on()"
}

void heat() {
    state.isDigital=true
    setThermostatMode("heat")
}

void emergencyHeat() {
    state.isDigital=true
    setThermostatMode("emergency heat")
}

void cool() {
    state.isDigital=true
    setThermostatMode("cool")
}

void auto() {
    state.isDigital=true
    setThermostatMode("auto")
}

void setThermostatFanMode(mode) {
    if (logEnable) log.debug "setThermostatFanMode($mode)"
    if (!getEnabledFanModes().contains(mode)) {
        log.warn "Fan mode '${mode}' is disabled in this device's preferences; ignoring."
        return
    }
    List<hubitat.zwave.Command> cmds = []
    if (logEnable) log.debug "setting zwave thermostat fan mode ${SET_THERMOSTAT_FAN_MODE[mode]}"
    cmds.add(zwave.thermostatFanModeV3.thermostatFanModeSet(fanMode: SET_THERMOSTAT_FAN_MODE[mode]))
    cmds.add(zwave.thermostatFanModeV3.thermostatFanModeGet())
    state.isDigital=true
    sendToDevice(cmds)
}

void fanOn() {
    state.isDigital=true
    setThermostatFanMode("on")
}

void fanAuto() {
    state.isDigital=true
    setThermostatFanMode("auto")
}

void fanCirculate() {
    state.isDigital=true
    setThermostatFanMode("circulate")
}

void setSchedule() {
    log.warn "setSchedule is not supported by this driver"
}
