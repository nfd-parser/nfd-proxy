nfd-proxy 代理服务  

# 打包: 
``` bash
mvn clean package
```

# 测试运行:
```bash
java -jar target/nfd-proxy.jar
```

# 部署运行:
1. 将src/main/resources/app.yml和target/nfd-proxy.jar上传至服务器, 这两个文件放到同一个目录下.
2. 运行jar包
```bash
nohup java -jar nfd-proxy.jar > out-nfd-proxy.log 2>&1 &
```

# 如何使用: 
前置条件: 拥有独立IP的服务器+jdk17环境

nfd-proxy的配置  
app.yml:
```yml
proxy-server:
  randUserPwd: false #是否随机生成用户名密码, 启用此功能会更安全, 防止代理被滥用
  type: http # 目前仅支持http隧道
  port: 8899
  # 线上建议配置用户名密码
  username: 您的用户名
  password: 您的密码
```
如果配置了随机生成用户名密码项目启动后会打印用户名和密码
```
 =============server info=================
2024-12-18 14:35:44.395 INFO  -> [ntloop-thread-0] cn.qaiu.vx.core.Deploy                   :
port: 8899
username: xxx
password: xxx
```

nfd-proxy部署后在netdisk-fast-download所在服务器添加配置

app-dev.yml: 
```yml
### 支持多个代理IP代理不同类型的网盘请求
proxy:
  # 配置1
  - panTypes: pod,pgd,pgd #网盘类型标识, 支持多个用逗号隔开 OneDrive,GoogleDrive,Dropbox
    type: http # 支持http/socks4/socks5
    host: 您的IP
    port: 8899
    username: 您的用户名
    password: 您的密码
  # 配置2
  - panTypes: fj,ye,iz #网盘类型标识, 支持多个用逗号隔开
    type: http # 支持http/socks4/socks5
    host: 您的IP
    port: 8899
    username: 您的用户名
    password: 您的密码
    
```
