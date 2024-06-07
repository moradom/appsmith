// Check if user is updating the app when toast is shown
// Check how many times does the user see a toast before updating

import { toast } from "design-system";
import {
  createMessage,
  INFO_VERSION_MISMATCH_FOUND_RELOAD_REQUEST,
} from "@appsmith/constants/messages";
import type { AppVersionData } from "@appsmith/configs/types";
import {
  getVersionUpdateState,
  removeVersionUpdateState,
  setVersionUpdateState,
} from "utils/storage";
import AnalyticsUtil from "@appsmith/utils/AnalyticsUtil";

enum UpdateStateEvent {
  PROMPT_SHOWN = "PROMPT_SHOWN",
  UPDATE_REQUESTED = "UPDATE_REQUESTED",
}

export interface VersionUpdateState {
  currentVersion: string;
  upgradeVersion: string;
  timesShown: number;
  event: UpdateStateEvent;
}

let timesShown = 0;

function showPrompt(newUpdateState: VersionUpdateState) {
  toast.show(createMessage(INFO_VERSION_MISMATCH_FOUND_RELOAD_REQUEST), {
    kind: "info",
    autoClose: false,
    action: {
      text: "refresh",
      effect: () => handleUpdateRequested(newUpdateState),
    },
  });
}

function handleUpdateRequested(newUpdateState: VersionUpdateState) {
  // store version update with timesShown counter
  setVersionUpdateState({
    ...newUpdateState,
    event: UpdateStateEvent.UPDATE_REQUESTED,
  }).then(() => {
    AnalyticsUtil.logEvent("VERSION_UPDATE_REQUESTED", {
      fromVersion: newUpdateState.currentVersion,
      toVersion: newUpdateState.upgradeVersion,
      timesShown,
    });
    // Reload to fetch the latest app version
    location.reload();
  });
}

export async function handleVersionUpdate(
  currentVersionData: AppVersionData,
  serverVersion: string,
) {
  // If no version is set, ignore
    return
}

