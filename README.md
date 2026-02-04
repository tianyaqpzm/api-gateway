# 整体架构

**ces** 


整体架构设计
我们将系统分为四层：接入层、应用层、数据层、基础设施层。

```Mermaid
graph TD
    %% ==========================================
    %% 外部层
    %% ==========================================
    User(("用户 / 前端"))
    LLM_Provider["外部大模型 API\n(OpenAI / DeepSeek)"]
    
    %% ==========================================
    %% 网关层 (Spring Cloud Gateway)
    %% ==========================================
    subgraph "接入层 (DMZ)"
        Gateway["API Gateway\n(Spring Cloud Gateway + WebFlux)"]
        style Gateway fill:#f3e5f5,stroke:#4a148c
    end

    %% ==========================================
    %% 服务治理
    %% ==========================================
    subgraph "基础设施 (Infra)"
        Nacos["Nacos Cluster\n(注册 & 配置中心)"]
        style Nacos fill:#e3f2fd,stroke:#0d47a1
    end

    %% ==========================================
    %% 内部服务层
    %% ==========================================
    subgraph "应用层 (Microservices)"
        %% Java 服务
        JavaApp["Java 业务服务\n(Spring Boot + LangChain4j)\n职责：RAG, 业务工具, MCP Host"]
        style JavaApp fill:#e8f5e9,stroke:#1b5e20
        
        %% Python 服务
        PythonApp["Python Agent 服务\n(FastAPI + LangGraph)\n职责：复杂推理, 编排"]
        style PythonApp fill:#fff3e0,stroke:#e65100
        
        %% 互通
        JavaApp <==>|"内部调用 (HTTP/RPC)"| PythonApp
        JavaApp -.-> Nacos
        PythonApp -.-> Nacos
    end

    %% ==========================================
    %% 数据层
    %% ==========================================
    subgraph "数据层 (Persistence)"
        PgVector[("PostgreSQL (PgVector)\n长期记忆")]
        Mongo[("MongoDB\n短期记忆 (Chat Memory)")]
    end

    %% ==========================================
    %% 连线
    %% ==========================================
    User ==>|"1. HTTPS/WebSocket"| Gateway
    Gateway ==>|"2. 路由分发"| JavaApp
    Gateway ==>|"2. 路由分发"| PythonApp
    
    JavaApp --> PgVector
    JavaApp --> Mongo
    
    %% AI Proxy 模式
    JavaApp -.->|"3. 请求 LLM"| Gateway
    PythonApp -.->|"3. 请求 LLM"| Gateway
    Gateway -.->|"4. 鉴权/计费/审计"| LLM_Provider
```







[Mermaid地址](https://mermaid.ai/app/projects/a2d2c1bf-fa7d-4bc5-a183-c94a3dd48f2c/diagrams/df70991c-1474-4514-b0bb-d50b92c7770e/version/v0.1/edit)
```Mermaid
graph TD
    %% 样式定义
    classDef java fill:#e1f5fe,stroke:#01579b,stroke-width:2px;
    classDef db fill:#fff3e0,stroke:#e65100,stroke-width:2px;
    classDef gateway fill:#f3e5f5,stroke:#4a148c,stroke-width:2px;
    classDef ext fill:#e8f5e9,stroke:#1b5e20,stroke-width:2px;

    User((用户)) -->|HTTPS| Ingress[(API Gateway
    入口网关)]:::gateway
    
    subgraph "核心业务域 (Private Cloud)"
        Ingress -->|路由转发| Agent[(智能体Agent
        Spring Boot + LangChain4j)]:::java
        
        Agent <-->|读写历史| Mongo[(MongoDB\n长期记忆)]:::db
        Agent <-->|向量检索| VectorDB[(PgVector\n长期知识库)]:::db
    end
    
    subgraph "MCP 工具生态"
        Agent <==>|MCP 协议| ToolA[(MCP Server\n数据库查询工具)]:::ext
        Agent <==>|MCP 协议| ToolB[(MCP Server\n网络搜索工具)]:::ext
    end

    subgraph "大模型服务域"
        Agent -->|构建 Prompt| Proxy[(API Gateway\n出口代理/审计)]:::gateway
        Proxy -->|鉴权 & 流控| LLM(大语言模型\nOpenAI / DeepSeek):::ext
    end
```



四个阶段：
MCP 的交互是一个标准化的生命周期，彻底解耦了调用方和实现方。

阶段一：建立连接 (Connection)

Python 发起 GET /mcp/sse。
Java 建立长连接，并立即推送 endpoint 事件，告知 Python：“我在，发消息请 POST 到 /mcp/messages?sessionId=xyz”。

阶段二：握手 (Handshake)
Python 发送 initialize 指令。
Java 返回协议版本和能力声明（Capabilities）。

阶段三：发现 (Discovery)
Python 发送 tools/list。
Java 扫描内部注册的 Bean（策略模式），返回工具清单（如 query_order, search_knowledge_base）及其 JSON Schema。

关键点：Python 的 LLM 此时“看到”了工具说明书。

阶段四：执行 (Execution)

LLM 决策调用工具，Python 发送 tools/call，带上参数 {"orderId": "8888"}。

Java 执行业务逻辑（查库），将结果通过 SSE 推送回 Python。



Connection: 只要连上，Java 立刻告诉 Python “发消息的地址”。

Handshake: 互报家门，确认版本： 
 协议版本协商： [initialize] Session: 05137a86-f3bf-4016-986e-736e4eb1accd
 握手完成通知:  [notifications/initialized] Session: 05137a86-f3bf-4016-986e-736e4eb1accd

Discovery: Python 问“你会啥？”，Java 答“我会查订单”。

Execution: Python 说“查一下 CN-8888”，Java 查完把结果推回来。

```Mermaid
sequenceDiagram
    autonumber
    participant Py as Python Client<br>(MCP Client)
    participant Java as Java Backend<br>(MCP Server)
    participant Tool as OrderQueryTool<br>(Java Bean)

    rect rgb(227, 242, 253)
    note right of Py: Phase 1: 建立连接 (Connection)
    Py->>Java: GET /mcp/sse
    activate Java
    Note right of Java: 1. 创建 Session<br>2. 保持 SSE 长连接
    Java-->>Py: SSE Event: "endpoint"<br>data: "/mcp/messages?sessionId=abc"
    end

    rect rgb(255, 243, 224)
    note right of Py: Phase 2: 握手协商 (Handshake)
    Py->>Java: POST /mcp/messages?sessionId=abc<br>{jsonrpc: "2.0", method: "initialize"}
    Note right of Java: 检查协议版本<br>声明自身能力
    Java-->>Py: SSE Event: "message"<br>{result: {protocolVersion: "2024...", capabilities: ...}}
    
    Py->>Java: POST /mcp/messages?sessionId=abc<br>{method: "notifications/initialized"}
    Note right of Java: 握手完成，无需回复
    end

    rect rgb(232, 245, 233)
    note right of Py: Phase 3: 能力发现 (Discovery)
    Py->>Java: POST /mcp/messages?sessionId=abc<br>{method: "tools/list"}
    Java->>Java: 扫描 toolRegistry
    Java-->>Py: SSE Event: "message"<br>{result: {tools: [{name: "query_order", inputSchema: ...}]}}
    Note left of Py: Python 获取到工具清单<br>LLM 决定调用 query_order
    end

    rect rgb(252, 228, 236)
    note right of Py: Phase 4: 业务执行 (Execution)
    Py->>Java: POST /mcp/messages?sessionId=abc<br>{method: "tools/call", params: {name: "query_order", args: {orderId: "CN-8888"}}}
    
    activate Java
    Java->>Tool: execute(args)
    activate Tool
    Note right of Tool: 执行真实业务逻辑<br>(查数据库/调接口)
    Tool-->>Java: return ToolResult("状态: 已发货")
    deactivate Tool
    
    Java-->>Py: SSE Event: "message"<br>{result: {content: [{type: "text", text: "状态: 已发货"}]}}
    deactivate Java
    end
```

上半部分（蓝色区域）：用户正在聊天，Python 边思考、边输出、边写入数据库。

下半部分（橙色区域）：用户回头看历史，Java 直接去数据库捞数据展示。

```Mermaid
sequenceDiagram
    autonumber
    actor User as 用户 (User)
    participant GW as Gateway (网关)
    participant Py as Python Agent (大脑)<br>Writer
    participant Java as Java Service (后台)<br>Reader
    participant DB as PostgreSQL (共享数据库)

    rect rgb(227, 242, 253)
    note right of User: 🟢 场景一：当前正在对话 (Python 直写)
    
    User->>GW: 1. 发送消息: "你好"
    GW->>Py: 2. 路由转发 (SSE连接)
    
    activate Py
    note right of Py: LangGraph 启动思考
    
    Py->>DB: 3. UPSERT Thread State
    note right of Py: 写入短期记忆 (Checkpoint)<br>用于多轮对话上下文
    
    Py-->>User: 4. SSE 流式响应: "你..."
    Py-->>User: 4. SSE 流式响应: "好..."
    Py-->>User: 4. SSE 流式响应: "!"
    
    Py->>DB: 5. INSERT chat_history
    note right of Py: 写入持久化记录<br>(用户看的那种 Q&A)
    
    Py-->>User: 6. SSE End (结束)
    deactivate Py
    end

    rect rgb(255, 243, 224)
    note right of User: 🟠 场景二：查看历史记录 (Java 只读)
    
    User->>GW: 7. 点击"历史记录" (GET /api/history)
    GW->>Java: 8. 路由转发
    
    activate Java
    Java->>DB: 9. SELECT * FROM chat_history<br>WHERE user_id = ...
    DB-->>Java: 10. 返回结果集
    
    Java-->>User: 11. 返回 JSON 列表
    deactivate Java
    end
```
