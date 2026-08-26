"use client";

import { useEffect } from "react";
import { installResumeLensExtensionBridge } from "../extension-bridge";

export function ExtensionBridgeHost() {
  useEffect(() => installResumeLensExtensionBridge(), []);
  return null;
}
