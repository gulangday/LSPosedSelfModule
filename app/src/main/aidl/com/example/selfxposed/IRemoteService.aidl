package com.example.selfxposed;

/** 跑在 Shizuku 进程（shell 或 root 身份）里的命令执行服务。 */
interface IRemoteService {
    String runCommand(String command);
}
