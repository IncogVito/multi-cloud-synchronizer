export interface DeviceStatus {
  id: string;
  deviceType: string;
  isConnected: boolean;
  lastCheckedAt: string;
  details: Record<string, unknown>;
}
