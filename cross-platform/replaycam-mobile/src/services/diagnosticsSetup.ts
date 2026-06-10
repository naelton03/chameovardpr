import { appendDiagnosticLog } from './logger';

let installed = false;

export function installGlobalDiagnosticsHandlers() {
  if (installed) return;
  installed = true;

  const maybeGlobal = globalThis as unknown as {
    ErrorUtils?: {
      getGlobalHandler?: () => (error: Error, isFatal?: boolean) => void;
      setGlobalHandler?: (handler: (error: Error, isFatal?: boolean) => void) => void;
    };
  };

  const defaultHandler = maybeGlobal.ErrorUtils?.getGlobalHandler?.();
  maybeGlobal.ErrorUtils?.setGlobalHandler?.((error: Error, isFatal?: boolean) => {
    void appendDiagnosticLog(`GLOBAL_ERROR fatal=${Boolean(isFatal)} message=${error?.message ?? 'unknown'}`);
    if (defaultHandler) defaultHandler(error, isFatal);
  });

  const originalConsoleError = console.error;
  console.error = (...args: unknown[]) => {
    void appendDiagnosticLog(`CONSOLE_ERROR ${args.map((item) => String(item)).join(' | ')}`);
    originalConsoleError(...args);
  };
}
