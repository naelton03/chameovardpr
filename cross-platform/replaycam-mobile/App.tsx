import { useEffect } from 'react';
import { StatusBar } from 'expo-status-bar';
import { ReplayScreen } from './src/screens/ReplayScreen';
import { installGlobalDiagnosticsHandlers } from './src/services/diagnosticsSetup';

export default function App() {
  useEffect(() => {
    installGlobalDiagnosticsHandlers();
  }, []);

  return (
    <>
      <StatusBar style="light" />
      <ReplayScreen />
    </>
  );
}
