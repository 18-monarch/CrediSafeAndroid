interface LiveTripState {
  tripId: string;
  userId: string;
  latestFrame: any;
  lastUpdate: number;
  status: 'LIVE' | 'OFFLINE';
}

const liveTrips = new Map<string, LiveTripState>();

export function updateLiveTrip(tripId: string, userId: string, frame: any) {
  liveTrips.set(tripId, {
    tripId,
    userId,
    latestFrame: frame,
    lastUpdate: Date.now(),
    status: 'LIVE'
  });
}

export function getLiveTrips() {
  return Array.from(liveTrips.values());
}

export function endLiveTrip(tripId: string) {
  liveTrips.delete(tripId);
}

// Cleanup stale trips every minute
setInterval(() => {
  const now = Date.now();
  for (const [id, state] of liveTrips.entries()) {
    if (now - state.lastUpdate > 30000) { // 30 seconds timeout
      liveTrips.delete(id);
    }
  }
}, 60000);
