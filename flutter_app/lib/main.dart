import 'package:flutter/material.dart';

import 'replay_camera_page.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const ReplayOnApp());
}

class ReplayOnApp extends StatelessWidget {
  const ReplayOnApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'ReplayOn',
      debugShowCheckedModeBanner: false,
      theme: ThemeData.dark(useMaterial3: true),
      home: const ReplayCameraPage(),
    );
  }
}
