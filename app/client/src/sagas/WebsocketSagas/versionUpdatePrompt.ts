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
  return
}

function handleUpdateRequested(newUpdateState: VersionUpdateState) {
  return
}

export async function handleVersionUpdate(
  currentVersionData: AppVersionData,
  serverVersion: string,
) {
  // If no version is set, ignore
    return
}

