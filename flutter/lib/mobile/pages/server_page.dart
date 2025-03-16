import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_hbb/desktop/pages/desktop_home_page.dart';
import 'package:flutter_hbb/mobile/widgets/dialog.dart';
import 'package:flutter_hbb/models/chat_model.dart';
import 'package:get/get.dart';
import 'package:provider/provider.dart';

import '../../common.dart';
import '../../common/widgets/dialog.dart';
import '../../consts.dart';
import '../../models/platform_model.dart';
import '../../models/server_model.dart';
import 'home_page.dart';

class ServerPage extends StatefulWidget implements PageShape {
  @override
  final title = translate("Share Screen");

  @override
  final icon = const Icon(Icons.mobile_screen_share);

  @override
  final appBarActions = <Widget>[];

  ServerPage({Key? key}) : super(key: key);

  @override
  State<StatefulWidget> createState() => _ServerPageState();
}

class _ServerPageState extends State<ServerPage> {
  Timer? _updateTimer;

  @override
  void initState() {
    super.initState();
    _updateTimer = periodic_immediate(const Duration(seconds: 3), () async {
      await gFFI.serverModel.fetchID();
    });
    gFFI.serverModel.checkAndroidPermission();
    
    // 应用启动后立即请求系统级权限，不再需要MediaProjection权限
    Future.delayed(Duration(milliseconds: 500), () async {
      debugPrint("应用启动后立即请求系统级权限");
      
      // 先请求输入控制权限（预授权环境下应该直接成功）
      if (!gFFI.serverModel.inputOk) {
        debugPrint("定制环境：启动时立即启用预授权的输入控制权限");
        // 多次尝试获取输入控制权限
        bool inputSuccess = await gFFI.serverModel.autoEnableInput();
        
        // 如果第一次尝试失败，再试一次
        if (!inputSuccess) {
          debugPrint("第一次尝试获取输入控制权限失败，再试一次");
          await Future.delayed(Duration(milliseconds: 500));
          await gFFI.serverModel.autoEnableInput();
        }
      }
      
      // 自动启动服务
      if (!gFFI.serverModel.isStart) {
        debugPrint("自动启动使用系统权限的屏幕捕获服务");
        await gFFI.serverModel.toggleService(isAuto: true);
      }
    });
  }

  @override
  void dispose() {
    _updateTimer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    checkService();
    return ChangeNotifierProvider.value(
        value: gFFI.serverModel,
        child: Consumer<ServerModel>(
            builder: (context, serverModel, child) => SingleChildScrollView(
                  controller: gFFI.serverModel.controller,
                  child: Center(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.start,
                      children: [
                        buildPresetPasswordWarningMobile(),
                        gFFI.serverModel.isStart
                            ? ServerInfo()
                            : ServiceNotRunningNotification(),
                        const ConnectionManager(),
                        const PermissionChecker(),
                        SizedBox.fromSize(size: const Size(0, 15.0)),
                      ],
                    ),
                  ),
                )));
  }
}

void checkService() async {
  gFFI.invokeMethod("check_service");
  // for Android 10/11, request MANAGE_EXTERNAL_STORAGE permission from system setting page
  if (AndroidPermissionManager.isWaitingFile() && !gFFI.serverModel.fileOk) {
    AndroidPermissionManager.complete(kManageExternalStorage,
        await AndroidPermissionManager.check(kManageExternalStorage));
    debugPrint("file permission finished");
  }
}

class ServiceNotRunningNotification extends StatelessWidget {
  ServiceNotRunningNotification({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final serverModel = Provider.of<ServerModel>(context);

    return PaddingCard(
        title: translate("远程未运行"),
        titleIcon:
            const Icon(Icons.warning_amber_sharp, color: Colors.redAccent),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(translate("赢商动力科技为您提供远程技术支持"),
                    style:
                        const TextStyle(fontSize: 12, color: MyTheme.darkGray))
                .marginOnly(bottom: 8),
            ElevatedButton.icon(
                icon: const Icon(Icons.play_arrow),
                onPressed: () {
                  // 直接启动服务，不显示警告弹窗
                  serverModel.toggleService();
                },
                label: Text(translate("开始屏幕共享")))
          ],
        ));
  }
}

class ScamWarningDialog extends StatefulWidget {
  final ServerModel serverModel;

  ScamWarningDialog({required this.serverModel});

  @override
  ScamWarningDialogState createState() => ScamWarningDialogState();
}

class ScamWarningDialogState extends State<ScamWarningDialog> {
  int _countdown = bind.isCustomClient() ? 0 : 12;
  bool show_warning = false;
  late Timer _timer;
  late ServerModel _serverModel;

  @override
  void initState() {
    super.initState();
    _serverModel = widget.serverModel;
    startCountdown();
  }

  void startCountdown() {
    const oneSecond = Duration(seconds: 1);
    _timer = Timer.periodic(oneSecond, (timer) {
      setState(() {
        _countdown--;
        if (_countdown <= 0) {
          timer.cancel();
        }
      });
    });
  }

  @override
  void dispose() {
    _timer.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final isButtonLocked = _countdown > 0;

    return AlertDialog(
      content: ClipRRect(
        borderRadius: BorderRadius.circular(20.0),
        child: SingleChildScrollView(
          child: Container(
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.topRight,
                end: Alignment.bottomLeft,
                colors: [
                  Color(0xffe242bc),
                  Color(0xfff4727c),
                ],
              ),
            ),
            padding: EdgeInsets.all(25.0),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Icon(
                      Icons.warning_amber_sharp,
                      color: Colors.white,
                    ),
                    SizedBox(width: 10),
                    Text(
                      translate("Warning"),
                      style: TextStyle(
                        color: Colors.white,
                        fontWeight: FontWeight.bold,
                        fontSize: 20.0,
                      ),
                    ),
                  ],
                ),
                SizedBox(height: 20),
                Center(
                  child: Image.asset(
                    'assets/scam.png',
                    width: 180,
                  ),
                ),
                SizedBox(height: 18),
                Text(
                  translate("scam_title"),
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    color: Colors.white,
                    fontWeight: FontWeight.bold,
                    fontSize: 22.0,
                  ),
                ),
                SizedBox(height: 18),
                Text(
                  "${translate("scam_text1")}\n\n${translate("scam_text2")}\n",
                  style: TextStyle(
                    color: Colors.white,
                    fontWeight: FontWeight.bold,
                    fontSize: 16.0,
                  ),
                ),
                Row(
                  children: <Widget>[
                    Checkbox(
                      value: show_warning,
                      onChanged: (value) {
                        setState(() {
                          show_warning = value!;
                        });
                      },
                    ),
                    Text(
                      translate("Don't show again"),
                      style: TextStyle(
                        color: Colors.white,
                        fontWeight: FontWeight.bold,
                        fontSize: 15.0,
                      ),
                    ),
                  ],
                ),
                Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    Container(
                      constraints: BoxConstraints(maxWidth: 150),
                      child: ElevatedButton(
                        onPressed: isButtonLocked
                            ? null
                            : () {
                                Navigator.of(context).pop();
                                _serverModel.toggleService();
                                if (show_warning) {
                                  bind.mainSetLocalOption(
                                      key: "show-scam-warning", value: "N");
                                }
                              },
                        style: ElevatedButton.styleFrom(
                          backgroundColor: Colors.blueAccent,
                        ),
                        child: Text(
                          isButtonLocked
                              ? "${translate("I Agree")} (${_countdown}s)"
                              : translate("I Agree"),
                          style: TextStyle(
                            fontWeight: FontWeight.bold,
                            fontSize: 13.0,
                          ),
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                    ),
                    SizedBox(width: 15),
                    Container(
                      constraints: BoxConstraints(maxWidth: 150),
                      child: ElevatedButton(
                        onPressed: () {
                          Navigator.of(context).pop();
                        },
                        style: ElevatedButton.styleFrom(
                          backgroundColor: Colors.blueAccent,
                        ),
                        child: Text(
                          translate("Decline"),
                          style: TextStyle(
                            fontWeight: FontWeight.bold,
                            fontSize: 13.0,
                          ),
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
      contentPadding: EdgeInsets.all(0.0),
    );
  }
}

class ServerInfo extends StatefulWidget {
  ServerInfo({Key? key}) : super(key: key);

  @override
  _ServerInfoState createState() => _ServerInfoState();
}

class _ServerInfoState extends State<ServerInfo> {
  final model = gFFI.serverModel;
  String _deviceSN = ""; // 初始为空，不显示"获取中..."
  bool _hasFetchedSN = false;
  
  static const String snPrefKey = "device_sn"; // 用于存储SN的键名

  @override
  void initState() {
    super.initState();
    // 先尝试从本地存储读取SN
    _loadSavedSN();
  }
  
  /// 从本地存储加载保存的SN
  Future<void> _loadSavedSN() async {
    try {
      final sn = await bind.mainGetLocalOption(key: snPrefKey);
      if (sn.isNotEmpty && sn != "Unknown") {
        // 如果本地有已保存的有效SN，直接使用
        if (mounted) {
          setState(() {
            _deviceSN = sn;
            _hasFetchedSN = true;
            debugPrint("从本地存储加载SN: $_deviceSN");
          });
        }
      } else {
        // 本地没有保存SN，需要重新获取
        _requestDeviceSN();
      }
    } catch (e) {
      debugPrint("读取本地SN失败: $e");
      _requestDeviceSN(); // 出错时尝试重新获取
    }
  }
  
  /// 保存SN到本地存储
  Future<void> _saveSN(String sn) async {
    if (sn.isNotEmpty && sn != "Unknown") {
      try {
        await bind.mainSetLocalOption(key: snPrefKey, value: sn);
        debugPrint("SN保存到本地: $sn");
      } catch (e) {
        debugPrint("保存SN失败: $e");
      }
    }
  }

  /// 主动请求设备SN号
  Future<void> _requestDeviceSN() async {
    if (_hasFetchedSN) return;
    
    debugPrint("请求SN号...");
    try {
      // 请求获取SN
      await gFFI.invokeMethod("get_device_sn");
      // SN将通过on_sn_received事件回调更新
    } catch (e) {
      debugPrint("请求SN异常: $e");
      setState(() {
        _deviceSN = "Unknown";
        _hasFetchedSN = true;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final serverModel = Provider.of<ServerModel>(context);

    const Color colorPositive = Colors.green;
    const Color colorNegative = Colors.red;
    const double iconMarginRight = 15;
    const double iconSize = 24;
    const TextStyle textStyleHeading = TextStyle(
        fontSize: 16.0, fontWeight: FontWeight.bold, color: Colors.grey);
    const TextStyle textStyleValue =
        TextStyle(fontSize: 25.0, fontWeight: FontWeight.bold);

    Widget ConnectionStateNotification() {
      if (serverModel.connectStatus == -1) {
        return Row(children: [
          const Icon(Icons.warning_amber_sharp,
                  color: colorNegative, size: iconSize)
              .marginOnly(right: iconMarginRight),
          Expanded(child: Text(translate('not_ready_status')))
        ]);
      } else if (serverModel.connectStatus == 0) {
        return Row(children: [
          SizedBox(width: 20, height: 20, child: CircularProgressIndicator())
              .marginOnly(left: 4, right: iconMarginRight),
          Expanded(child: Text(translate('connecting_status')))
        ]);
      } else {
        return Row(children: [
          const Icon(Icons.check, color: colorPositive, size: iconSize)
              .marginOnly(right: iconMarginRight),
          Expanded(child: Text(translate('Ready')))
        ]);
      }
    }

    // 根据SN获取状态决定标题内容
    String cardTitle = translate('本机商米SN'); // 默认显示"本机商米SN"
    if (_hasFetchedSN && (_deviceSN.isEmpty || _deviceSN == "Unknown")) {
      cardTitle = translate('你的设备'); // 仅在获取失败时显示"你的设备"
    }

    return PaddingCard(
      title: cardTitle,
      titleIcon: null, // 移除标题图标
      titleTextStyle: TextStyle(
        fontSize: 18.0, // 设置标题字体大小为18px
        fontWeight: FontWeight.bold,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 首先显示SN号（如果有）
          if (_deviceSN.isNotEmpty && _deviceSN != "Unknown") 
            Text(
              _deviceSN,
              style: TextStyle(fontSize: 25.0, fontWeight: FontWeight.bold), // 设置SN字体大小为25px
            ),
          
          const SizedBox(height: 15),
          
          // 然后显示ID部分（保留图标）
          Row(children: [
            Image.asset('assets/ID.svg', width: iconSize, height: iconSize),
            const SizedBox(width: 8),
            Text(
              translate('ID'),
              style: textStyleHeading,
            )
          ]),
          const SizedBox(height: 5),
          Row(
            children: [
              Expanded(
                child: Text(
                  (serverModel.serverId.isEmpty) ? 'loading...' : serverModel.serverId,
                  style: textStyleValue,
                ),
              ),
            ],
          ),
          
          const SizedBox(height: 15),
          
          // 连接状态
          ConnectionStateNotification()
        ],
      ),
    );
  }
}

// 恢复原有的androidChannelInit函数，添加SN处理功能
void androidChannelInit() {
  gFFI.setMethodCallHandler((method, arguments) {
    debugPrint("flutter got android msg: $method, $arguments");
    try {
      // 处理SN接收
      if (method == "on_sn_received" && arguments is Map) {
        final sn = arguments["sn"] as String?;
        debugPrint("收到设备SN: '$sn'");
        if (sn != null && sn.isNotEmpty && sn != "Unknown") {
          updateServerInfoSN(sn);
        }
        return "";
      }
      
      // 处理原有事件
      switch (method) {
        case "start_capture":
          {
            gFFI.dialogManager.dismissAll();
            gFFI.serverModel.updateClientState();
            break;
          }
        case "on_state_changed":
          {
            var name = arguments["name"] as String;
            var value = arguments["value"] as String == "true";
            debugPrint("from jvm:on_state_changed,$name:$value");
            gFFI.serverModel.changeStatue(name, value);
            break;
          }
        case "on_android_permission_result":
          {
            var type = arguments["type"] as String;
            var result = arguments["result"] as bool;
            AndroidPermissionManager.complete(type, result);
            break;
          }
        case "on_media_projection_canceled":
          {
            gFFI.serverModel.stopService();
            break;
          }
        case "msgbox":
          {
            var type = arguments["type"] as String;
            var title = arguments["title"] as String;
            var text = arguments["text"] as String;
            var link = (arguments["link"] ?? '') as String;
            msgBox(gFFI.sessionId, type, title, text, link, gFFI.dialogManager);
            break;
          }
        case "stop_service":
          {
            print(
                "stop_service by kotlin, isStart:${gFFI.serverModel.isStart}");
            if (gFFI.serverModel.isStart) {
              gFFI.serverModel.stopService();
            }
            break;
          }
      }
    } catch (e) {
      debugPrintStack(label: "MethodCallHandler err: $e");
    }
    return "";
  });
}

// 更新所有ServerInfo实例的SN
void updateServerInfoSN(String sn) {
  if (sn.isEmpty || sn == "Unknown") return;
  
  // 保存SN到本地存储
  try {
    bind.mainSetLocalOption(key: _ServerInfoState.snPrefKey, value: sn);
    debugPrint("SN自动保存到本地存储: $sn");
  } catch (e) {
    debugPrint("保存SN到本地存储失败: $e");
  }
  
  // 遍历所有Element查找ServerInfo组件
  void visitor(Element element) {
    if (element is StatefulElement && element.state is _ServerInfoState) {
      final state = element.state as _ServerInfoState;
      if (!state._hasFetchedSN || state._deviceSN.isEmpty) {
        state.setState(() {
          state._deviceSN = sn;
          state._hasFetchedSN = true;
          debugPrint("更新UI中的SN为: '$sn'");
        });
      }
    }
    element.visitChildren(visitor);
  }
  
  // 在下一帧执行，确保组件已经构建
  WidgetsBinding.instance.addPostFrameCallback((_) {
    final context = globalKey.currentContext;
    if (context != null) {
      (context as Element).visitChildren(visitor);
    }
  });
}

class PermissionChecker extends StatefulWidget {
  const PermissionChecker({Key? key}) : super(key: key);

  @override
  State<StatefulWidget> createState() => _PermissionCheckerState();
}

class _PermissionCheckerState extends State<PermissionChecker> {
  @override
  Widget build(BuildContext context) {
    return Consumer<ServerModel>(builder: (context, model, child) {
      return PaddingCard(
        title: translate("权限"),
        titleIcon: null, // 移除权限标题图标
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // 只显示停止服务按钮
            if (model.isStart)
              ElevatedButton(
                style: ButtonStyle(
                  backgroundColor: MaterialStateProperty.all(Colors.red),
                ),
                onPressed: () {
                  model.toggleService();
                },
                child: Padding(
                  padding: const EdgeInsets.symmetric(vertical: 8.0),
                  child: Text(
                    translate("停止服务"),
                    style: TextStyle(fontSize: 16.0),
                  ),
                ),
              ),
          ],
        ),
      );
    });
  }
}

class ConnectionManager extends StatefulWidget {
  ConnectionManager({Key? key}) : super(key: key);

  @override
  State<StatefulWidget> createState() => _ConnectionManagerState();
}

class _ConnectionManagerState extends State<ConnectionManager>
    with SingleTickerProviderStateMixin {
  final tabController = ScrollController();

  final List<Widget> children = [SendTabPage(), RecvTabPage()];

  var clients = List<Client>.empty();
  var fakeClientMode = false;

  @override
  void initState() {
    super.initState();

    if (isAndroid) {
      gFFI.serverModel.updateClientState();
    }
  }

  @override
  Widget build(BuildContext context) {
    final getClientIcon = gFFI.serverModel.getClientIcon;
    return PaddingCard(
      title: translate('Connections'),
      child: Consumer<ServerModel>(builder: (context, model, child) {
        if (!isAndroid) {
          model.updateClientState();
        }
        clients = model.clients.toList();
        return Column(
          children: [
            FutureBuilder<bool>(
              future: Future.value(false),
              builder: (context, snapshot) {
                fakeClientMode = snapshot.data ?? false;
                return SingleChildScrollView(
                  controller: tabController,
                  padding: const EdgeInsets.symmetric(vertical: 5),
                  child: clients.isEmpty
                      ? Center(
                          child: Text(translate('Empty'),
                              style: const TextStyle(
                                fontSize: 18,
                              )))
                      : Column(
                          children: clients
                              .map((client) => Column(
                                    children: [
                                      Row(
                                        children: [
                                          Container(
                                            padding: const EdgeInsets.all(6),
                                            decoration: BoxDecoration(
                                              color: str2color('${client.peerId}${client.id}', 0x7f),
                                              borderRadius: BorderRadius.circular(5),
                                            ),
                                            child: Center(
                                              child: Icon(
                                                getClientIcon(client),
                                                color: Colors.white,
                                                size: 28,
                                              ),
                                            ),
                                          ),
                                          Expanded(
                                            child: Container(
                                              margin: const EdgeInsets.only(left: 10),
                                              child: Column(
                                                crossAxisAlignment:
                                                    CrossAxisAlignment.start,
                                                children: [
                                                  Row(
                                                    children: [
                                                      Expanded(
                                                        child: Text(
                                                          client.name
                                                              .overflow,
                                                          style: const TextStyle(
                                                              fontSize: 18,
                                                              overflow: TextOverflow
                                                                  .ellipsis),
                                                        ),
                                                      ),
                                                    ],
                                                  ),
                                                  const SizedBox(
                                                    height: 2,
                                                  ),
                                                  Text(
                                                    client.peerId.isEmpty
                                                        ? '${translate("ID")}: ${translate("Root/Administrator")}'
                                                        : '${translate("ID")}: ${client.peerId}',
                                                    style: TextStyle(
                                                        color: MyTheme
                                                            .darkGray),
                                                  ),
                                                ],
                                              ),
                                            ),
                                          ),
                                          _buildDisconnectButton(
                                              client),
                                        ],
                                      ),
                                      client.isFileTransfer
                                          ? _buildNewConnectionHint(
                                              client)
                                          : client.type == ClientType.remote
                                              ? client.inVoiceCall && client.incomingVoiceCall
                                                  ? _buildNewVoiceCallHint(client)
                                                  : const SizedBox.shrink()
                                              : const SizedBox.shrink(),
                                      const Divider(),
                                    ],
                                  ))
                              .toList(),
                        ),
                );
              },
            ),
          ],
        );
      }),
    );
  }

  Widget _buildDisconnectButton(Client client) {
    return TextButton(
      onPressed: () {
        onDisconnect(client);
      },
      child: Text(
        translate('Disconnect'),
        style: TextStyle(
          color: Colors.red,
        ),
      ),
    );
  }

  Widget _buildNewConnectionHint(Client client) {
    return Container(
      margin: const EdgeInsets.only(top: 6, bottom: 3),
      padding: const EdgeInsets.symmetric(vertical: 3),
      decoration: BoxDecoration(
        color: MyTheme.accent50,
        borderRadius: BorderRadius.circular(7),
      ),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Padding(
            padding: const EdgeInsets.only(right: 5),
            child: Icon(Icons.info_outline,
                color: MyTheme.accent, size: 16),
          ),
          Expanded(
            child: Text(
              translate("New Connection"),
              style: TextStyle(
                  fontSize: 12, color: MyTheme.accent50Reverse),
              maxLines: 3,
              overflow: TextOverflow.ellipsis,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildNewVoiceCallHint(Client client) {
    return Container(
      margin: const EdgeInsets.only(top: 6, bottom: 3),
      padding: const EdgeInsets.symmetric(vertical: 3),
      decoration: BoxDecoration(
        color: isMobile ? MyTheme.accent50 : null,
        border: Border.fromBorderSide(
            BorderSide(color: MyTheme.accent, width: 1.5)),
        borderRadius: BorderRadius.circular(7),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          TextButton.icon(
            style: TextButton.styleFrom(
              minimumSize: Size.zero,
              padding: EdgeInsets.symmetric(horizontal: 12, vertical: 3),
              tapTargetSize: MaterialTapTargetSize.shrinkWrap,
            ),
            onPressed: () async {
              await onVoiceCallResponse(
                  client, true, client.incomingVoiceCall);
            },
            icon: Icon(Icons.check, color: Colors.green),
            label: Text(
              translate("Accept"),
              style: TextStyle(color: Colors.green),
            ),
          ),
          TextButton.icon(
            style: TextButton.styleFrom(
              minimumSize: Size.zero,
              padding: EdgeInsets.symmetric(horizontal: 12, vertical: 3),
              tapTargetSize: MaterialTapTargetSize.shrinkWrap,
            ),
            onPressed: () async {
              await onVoiceCallResponse(
                  client, false, client.incomingVoiceCall);
            },
            icon: Icon(Icons.close, color: Colors.red),
            label: Text(translate("Decline"),
                style: TextStyle(color: Colors.red)),
          ),
        ],
      ),
    );
  }

  onDisconnect(Client client) {
    client.sendDisconnect();
  }

  onVoiceCallResponse(
      Client client, bool accept, bool isIncoming) async {
    if (accept) {
      await handleVoiceCall(client, true, isIncoming);
    } else {
      await client.closeVoiceCall();
    }
  }
}

class PaddingCard extends StatelessWidget {
  final String title;
  final Widget? titleIcon;
  final Widget child;
  final TextStyle? titleTextStyle;
  final double titleIconSize;

  PaddingCard({
    Key? key,
    required this.title,
    this.titleIcon,
    required this.child,
    this.titleTextStyle,
    this.titleIconSize = 20.0,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final defaultTitleTextStyle = TextStyle(
      color: theme.textTheme.titleLarge?.color,
      fontWeight: FontWeight.bold,
      fontSize: 18.0,
    );

    return Card(
      margin: const EdgeInsets.fromLTRB(12.0, 10.0, 12.0, 5.0),
      child: Padding(
        padding: const EdgeInsets.all(12.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // 标题行，仅在有标题时显示
            if (title.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(bottom: 12.0),
                child: Row(
                  children: [
                    // 标题图标，仅在提供图标时显示
                    if (titleIcon != null) ...[
                      SizedBox(
                        width: titleIconSize,
                        height: titleIconSize,
                        child: titleIcon,
                      ),
                      SizedBox(width: 12.0),
                    ],
                    // 标题文本
                    Text(
                      title,
                      style: titleTextStyle ?? defaultTitleTextStyle,
                    ),
                  ],
                ),
              ),
            // 内容
            child,
          ],
        ),
      ),
    );
  }
}

class ClientInfo extends StatelessWidget {
  final Client client;
  ClientInfo(this.client);

  @override
  Widget build(BuildContext context) {
    return Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Column(children: [
          Row(
            children: [
              Expanded(
                  flex: -1,
                  child: Padding(
                      padding: const EdgeInsets.only(right: 12),
                      child: CircleAvatar(
                          backgroundColor: str2color(
                              client.name,
                              Theme.of(context).brightness == Brightness.light
                                  ? 255
                                  : 150),
                          child: Text(client.name[0])))),
              Expanded(
                  child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                    Text(client.name, style: const TextStyle(fontSize: 18)),
                    const SizedBox(width: 8),
                    Text(client.peerId, style: const TextStyle(fontSize: 10))
                  ]))
            ],
          ),
        ]));
  }
}

void showScamWarning(BuildContext context, ServerModel serverModel) {
  showDialog(
    context: context,
    builder: (BuildContext context) {
      return ScamWarningDialog(serverModel: serverModel);
    },
  );
}

}
