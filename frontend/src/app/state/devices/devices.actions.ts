import { DeviceStatusResponse } from '../../core/api/generated/model/deviceStatusResponse';

export class LoadDevices {
  static readonly type = '[Devices] Load all device statuses';
}

export class UpdateDevice {
  static readonly type = '[Devices] Update single device status';
  constructor(public readonly status: DeviceStatusResponse) {}
}

export class StartPolling {
  static readonly type = '[Devices] Start polling';
}

export class StopPolling {
  static readonly type = '[Devices] Stop polling';
}
