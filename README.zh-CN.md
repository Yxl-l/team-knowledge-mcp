# Team Knowledge MCP

面向开发团队的工程知识库，可将规范、设计决策、排障经验与来源引用存入 PostgreSQL，再通过 MCP 按项目范围检索。服务支持关键词和代码标识检索，保留修订记录与验证状态。每个实例适用于一组相互信任的用户。项目范围用于检索，团队隔离请分别部署。

需要 Java 17、Maven 3.6.3+ 和 Docker Compose。也可以在已有 PostgreSQL 上准备一个专用空库，并通过 TEAM_KB_DB_URL 等环境变量指定连接。

1. 复制 .env.example 为 .env，填写独立的数据库密码。
2. 执行 `docker compose up -d --wait db`，再执行 `mvn verify`。
3. 执行 `java -jar target/team-knowledge-mcp-0.1.0.jar --spring.flyway.enabled=true`，首次启动时显式创建表。
4. 另开一个终端，在项目目录执行 `java -jar target/team-knowledge-mcp-0.1.0.jar admin --apply=true --action=import --root=examples --file=order-retries.json`，导入一条虚构的演示知识。
5. 在支持 Streamable HTTP 的 MCP 客户端填写 `http://127.0.0.1:18765/mcp`。先发现真实知识范围，再用 examples/search.json 提供的范围和关键词检索。

默认监听本机，MCP 默认只开放查询。团队部署通过受限网络或 HTTPS 代理访问，设置允许的主机和随机访问令牌。默认数据为空，团队按自身项目、模块、客户、版本、分支和数据库口径维护资料。完整配置、维护方式及测试说明见 [README.md](README.md)。
