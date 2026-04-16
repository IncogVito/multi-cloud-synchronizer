import { Injectable } from '@angular/core';
import { Action, Actions, ofActionDispatched, Selector, State, StateContext } from '@ngxs/store';
import { interval, switchMap, tap, takeUntil, catchError, EMPTY } from 'rxjs';
import { StatusService } from '../../core/api/generated/status/status.service';
import { DeviceStatusResponse } from '../../core/api/generated/model/deviceStatusResponse';
import { LoadDevices, StartPolling, StopPolling, UpdateDevice } from './devices.actions';

/** Interval between automatic device status refreshes. Drive polls every 10s. */
const DEVICE_POLL_INTERVAL_MS = 10_000;

export interface DevicesStateModel {
  devices: DeviceStatusResponse[];
  polling: boolean;
  error: string | null;
}

@State<DevicesStateModel>({
  name: 'devices',
  defaults: {
    devices: [],
    polling: false,
    error: null,
  },
})
@Injectable()
export class DevicesState {
  constructor(
    private readonly statusService: StatusService,
    private readonly actions$: Actions,
  ) {}

  @Selector()
  static devices(state: DevicesStateModel): DeviceStatusResponse[] {
    return state.devices;
  }

  @Selector()
  static polling(state: DevicesStateModel): boolean {
    return state.polling;
  }

  @Selector()
  static error(state: DevicesStateModel): string | null {
    return state.error;
  }

  @Action(LoadDevices)
  loadDevices(ctx: StateContext<DevicesStateModel>) {
    return this.statusService.getDeviceStatuses().pipe(
      tap(freshDevices => ctx.patchState({ devices: freshDevices, error: null })),
      catchError(err => {
        ctx.patchState({ error: err?.message ?? 'Failed to load device statuses' });
        return EMPTY;
      }),
    );
  }

  @Action(UpdateDevice)
  updateDevice(ctx: StateContext<DevicesStateModel>, action: UpdateDevice): void {
    const currentDevices = ctx.getState().devices;
    const deviceExists = currentDevices.some(d => d.deviceType === action.status.deviceType);

    const updatedDevices = deviceExists
      ? currentDevices.map(d => d.deviceType === action.status.deviceType ? action.status : d)
      : [...currentDevices, action.status];

    ctx.patchState({ devices: updatedDevices });
  }

  /**
   * Starts background polling.
   * Loads devices immediately on start, then polls every DEVICE_POLL_INTERVAL_MS.
   * Polling stops automatically when StopPolling is dispatched.
   *
   * TODO: Test that polling stops cleanly when component destroys and re-creates.
   * TODO: Test that rapid StartPolling/StopPolling calls don't leave orphaned intervals.
   */
  @Action(StartPolling)
  startPolling(ctx: StateContext<DevicesStateModel>) {
    ctx.patchState({ polling: true });
    ctx.dispatch(new LoadDevices());

    return interval(DEVICE_POLL_INTERVAL_MS).pipe(
      takeUntil(this.actions$.pipe(ofActionDispatched(StopPolling))),
      switchMap(() => this.statusService.getDeviceStatuses()),
      tap(freshDevices => ctx.patchState({ devices: freshDevices })),
      catchError(() => EMPTY),
    );
  }

  @Action(StopPolling)
  stopPolling(ctx: StateContext<DevicesStateModel>): void {
    ctx.patchState({ polling: false });
  }
}
