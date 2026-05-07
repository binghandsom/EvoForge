package com.evoforge.device

interface DeviceCommandDispatcher {
    DeviceTaskEvent dispatch(DeviceCommandMessage command)
}
