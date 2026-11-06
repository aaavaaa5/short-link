# 短链接管理平台 — 前端开发提示词

> 本文档供 AI 代码生成使用，可直接作为 Prompt 输入给 Claude/GPT 生成前端代码。

---

## 一、技术栈要求

```
前端框架:  React 18 + TypeScript
UI 组件库: Ant Design 5
路由:      React Router v6
状态管理:  Zustand (轻量) 或 React Context
HTTP 请求: Axios
图表:      ECharts (统计页)
构建工具:  Vite
Node.js:   18+
包管理器:  pnpm
```

---

## 二、全局架构

```
页面路由树:
├── /login              登录页 (无需 Token)
├── /register           注册页 (无需 Token)
├── /dashboard          仪表盘首页
│   └── /dashboard      总览统计卡片 + 近7日趋势图
├── /links              短链接管理
│   ├── /links          短链接列表 (分页 + 搜索 + 批量操作)
│   └── /links/create   创建短链接
├── /groups             分组管理
│   └── /groups         分组列表 (增删改)
└── /stats              访问统计
    └── /stats/:linkId  单个短链接统计详情 (折线图 + 地域分布)
```

### 全局规范

```
1. 所有 /api/** 请求自动在 Header 中携带 Authorization: Bearer {token}
2. Token 存储在 localStorage，key 为 "token"
3. 请求返回格式统一为 { code: number, message: string, data: T }
4. code === 200 表示成功，其他为错误，登录过期 code === 401 自动跳转 /login
5. 使用 Ant Design 的 message.success() / message.error() 显示操作结果
6. 分页参数统一为 page(从1开始) + size(默认10)
7. 所有表单使用 Ant Design Form 组件 + 校验规则
```

---

## 三、API 接口文档

> Base URL: `http://localhost:9000` (Gateway)

### 3.1 认证模块

#### POST /api/auth/register — 注册

```
Request Body (JSON):
{
    "username": "admin",            // 必填，2-50位
    "password": "123456",           // 必填，6-32位
    "realName": "张三",            // 必填，最长50位
    "email": "admin@test.com",     // 可选，邮箱格式
    "phone": "13800138000"         // 可选，手机号格式
}

Response:
{
    "code": 200,
    "message": "操作成功",
    "data": null
}
// 可能的错误: code=409 "用户名已存在"
```

#### POST /api/auth/login — 登录

```
Request Body (JSON):
{
    "username": "admin",            // 必填
    "password": "123456"            // 必填
}

Response:
{
    "code": 200,
    "message": "操作成功",
    "data": {
        "token": "eyJhbGciOiJIUzI1NiJ9...",   // JWT Token，后续请求携带
        "userId": 1,
        "username": "admin",
        "realName": "系统管理员"
    }
}
// 可能的错误: code=401 "用户名或密码错误", code=403 "账号已被停用"
```

---

### 3.2 短链接管理

#### POST /api/link — 创建短链接

```
Header: Authorization: Bearer {token}
Request Body (JSON):
{
    "originUrl": "https://www.example.com/very/long/url",  // 必填，≤2048字符
    "gid": "default",              // 必填，分组标识
    "title": "示例链接",           // 可选，≤100字符
    "describe": "这是一个示例",    // 可选，≤500字符
    "redirectType": 0,             // 可选，0=302临时(默认), 1=301永久
    "expireType": 0,               // 可选，0=永久有效(默认), 1=自定义过期
    "expireTime": null,            // 可选，expireType=1时必填，ISO日期格式
    "createdType": 1               // 可选，1=管理后台创建(默认)
}

Response:
{
    "code": 200,
    "message": "操作成功",
    "data": {
        "id": 1,
        "shortUri": "aB3xK9",
        "fullShortUrl": "http://s.local/aB3xK9",
        "originUrl": "https://www.example.com/very/long/url",
        "gid": "default",
        "title": "示例链接",
        "describe": "这是一个示例",
        "redirectType": 0,
        "expireType": 0,
        "expireTime": null,
        "totalPv": 0,
        "totalUv": 0,
        "totalIpCount": 0,
        "todayPv": 0,
        "todayUv": 0,
        "todayIpCount": 0,
        "enableStatus": 1,
        "createdType": 1,
        "createUserId": 1,
        "createTime": "2026-06-09T10:30:00",
        "updateTime": "2026-06-09T10:30:00"
    }
}
```

#### PUT /api/link — 更新短链接

```
Header: Authorization: Bearer {token}
Request Body (JSON):
{
    "id": 1,                       // 必填
    "originUrl": "...",            // 可选，只传需要更新的字段
    "gid": "...",                  // 可选
    "title": "新标题",             // 可选
    "describe": "...",             // 可选
    "redirectType": 1,             // 可选
    "expireType": 1,               // 可选
    "expireTime": "2026-12-31T23:59:59",  // 可选
    "enableStatus": 0              // 可选，0=停用, 1=启用
}

Response:
{ "code": 200, "message": "操作成功", "data": null }
```

#### DELETE /api/link/{id} — 删除短链接（逻辑删除）

```
Header: Authorization: Bearer {token}
Path: /api/link/1

Response:
{ "code": 200, "message": "操作成功", "data": null }
// 可能的错误: code=404 "短链接不存在"
```

#### GET /api/link/page — 分页查询短链接

```
Header: Authorization: Bearer {token}
Query Parameters:
    page=1            // 当前页码，默认1
    size=10           // 每页条数，默认10
    gid=default       // 可选，按分组筛选
    shortUri=aB3      // 可选，按短码模糊搜索
    originUrl=example // 可选，按原始URL模糊搜索
    title=示例        // 可选，按标题模糊搜索
    enableStatus=1    // 可选，按状态筛选
    createdType=1     // 可选，按创建类型筛选

Response:
{
    "code": 200,
    "message": "操作成功",
    "data": {
        "records": [              // 短链接列表 (LinkVO[] 结构同上)
            { "id": 1, "shortUri": "aB3xK9", "fullShortUrl": "http://s.local/aB3xK9", ... },
            { "id": 2, "shortUri": "cD4yL2", "fullShortUrl": "http://s.local/cD4yL2", ... }
        ],
        "total": 100,             // 总条数
        "size": 10,               // 每页条数
        "current": 1,             // 当前页码
        "pages": 10               // 总页数
    }
}
```

#### GET /api/link/{id} — 查询短链接详情

```
Header: Authorization: Bearer {token}
Path: /api/link/1

Response:
{
    "code": 200,
    "message": "操作成功",
    "data": { "id": 1, "shortUri": "aB3xK9", "fullShortUrl": "http://s.local/aB3xK9", ... }
}
// 可能的错误: code=404 "短链接不存在"
```

---

### 3.3 分组管理

#### POST /api/group — 创建分组

```
Header: Authorization: Bearer {token}
Request Body (JSON):
{
    "name": "营销活动",            // 必填，1-50位
    "description": "双11活动链接", // 可选，≤200位
    "sortOrder": 1                // 可选，数值越小越靠前
}

Response:
{ "code": 200, "message": "操作成功", "data": null }
```

#### PUT /api/group — 更新分组

```
Header: Authorization: Bearer {token}
Request Body (JSON):
{
    "id": 1,                       // 必填（更新时传）
    "name": "新名称",              // 可选
    "description": "新描述",       // 可选
    "sortOrder": 2                // 可选
}

Response:
{ "code": 200, "message": "操作成功", "data": null }
```

#### DELETE /api/group/{id} — 删除分组

```
Header: Authorization: Bearer {token}
Path: /api/group/1

Response:
{ "code": 200, "message": "操作成功", "data": null }
// 可能的错误: code=409 "分组下存在 N 条短链接，请先删除或移出"
```

#### GET /api/group/list — 查询所有分组

```
Header: Authorization: Bearer {token}

Response:
{
    "code": 200,
    "message": "操作成功",
    "data": [
        {
            "id": 1,
            "gid": "a1b2c3d4e5f6",
            "name": "默认分组",
            "description": "系统默认分组",
            "sortOrder": 0,
            "linkCount": 15,        // 分组下短链接数量
            "createTime": "2026-06-09T10:30:00"
        },
        {
            "id": 2,
            "gid": "f6e5d4c3b2a1",
            "name": "营销活动",
            "description": "双11活动链接",
            "sortOrder": 1,
            "linkCount": 3,
            "createTime": "2026-06-09T11:00:00"
        }
    ]
}
```

---

### 3.4 统计分析

#### GET /api/stats/link/{linkId}?range=today — 单个短链接统计

```
Header: Authorization: Bearer {token}
Path: /api/stats/link/1
Query: range=today   // today | 7d | 30d

Response:
{
    "code": 200,
    "message": "操作成功",
    "data": {
        "linkId": 1,
        "shortUri": "aB3xK9",
        "originUrl": "https://www.example.com/very/long/url",
        "totalPv": 15280,
        "totalUv": 8920,
        "totalIpCount": 3450,
        "todayPv": 128,
        "todayUv": 87,
        "todayIpCount": 43,
        "dailyStats": [             // 每日明细，用于折线图
            { "date": "2026-06-03", "pv": 210, "uv": 145, "ipCount": 67 },
            { "date": "2026-06-04", "pv": 185, "uv": 132, "ipCount": 55 },
            { "date": "2026-06-05", "pv": 230, "uv": 168, "ipCount": 78 },
            { "date": "2026-06-06", "pv": 198, "uv": 140, "ipCount": 62 },
            { "date": "2026-06-07", "pv": 245, "uv": 175, "ipCount": 82 },
            { "date": "2026-06-08", "pv": 156, "uv": 110, "ipCount": 51 },
            { "date": "2026-06-09", "pv": 128, "uv": 87,  "ipCount": 43 }
        ]
    }
}
```

#### GET /api/stats/overall — 总量统计

```
Header: Authorization: Bearer {token}

Response:
{
    "code": 200,
    "message": "操作成功",
    "data": {
        "totalPv": 128500,
        "totalUv": 45200,
        "totalIpCount": 18900
    }
}
```

---

### 3.5 用户信息

#### GET /api/user/me — 当前登录用户信息

```
Header: Authorization: Bearer {token}

Response:
{
    "code": 200,
    "message": "操作成功",
    "data": {
        "id": 1,
        "username": "admin",
        "realName": "系统管理员",
        "email": "admin@test.com",
        "phone": "13800138000",
        "enableStatus": 1,
        "createTime": "2026-06-01T00:00:00"
    }
}
// 注意：password 字段已脱敏，不返回
```

---

## 四、页面功能描述

### 4.1 登录页 `/login`

```
布局: 居中卡片式，左侧品牌 Logo + 右侧登录表单
功能:
  - 用户名输入框 (前缀 UserOutlined 图标)
  - 密码输入框 (前缀 LockOutlined 图标，输入时隐藏)
  - "记住我" 复选框
  - 登录按钮 (主色调，loading 态)
  - 底部 "没有账号？立即注册" 链接跳转 /register
校验:
  - 用户名 2-50 位
  - 密码 6-32 位
交互:
  - 登录成功 → 存储 token 到 localStorage → 跳转 /dashboard
  - 登录失败 → message.error("用户名或密码错误")
  - 账号停用 → message.error("账号已被停用")
```

### 4.2 注册页 `/register`

```
布局: 居中卡片式
功能:
  - 用户名、密码、确认密码、真实姓名、邮箱(可选)、手机号(可选)
  - 注册按钮 + "已有账号？去登录" 链接
校验:
  - 两次密码不一致时提示
  - 邮箱格式 / 手机号格式
交互:
  - 注册成功 → message.success("注册成功") → 跳转 /login
  - 用户名已存在 → message.error("用户名已存在")
```

### 4.3 仪表盘 `/dashboard`

```
布局: 顶部统计卡片行 + 下方图表区
卡片(4个):
  - 总短链接数 (带链接图标)
  - 总 PV (带眼睛图标)
  - 总 UV (带用户图标)
  - 总 IP 数 (带地球图标)
  - 每张卡片有 Trend 箭头 (今日 vs 昨日)
图表区:
  - 左侧: 近 7 日 PV/UV 趋势折线图 (ECharts)
  - 右侧: 今日热门短链接 Top10 表格
数据来源: GET /api/stats/overall
```

### 4.4 短链接列表 `/links`

```
布局: 顶部搜索栏 + 操作按钮行 + 表格 + 底部分页
搜索栏:
  - 短码输入框 (模糊搜索)
  - 原始URL输入框 (模糊搜索)
  - 分组下拉选择 (数据来自 GET /api/group/list)
  - 状态下拉: 全部 / 启用 / 停用
  - 搜索按钮 + 重置按钮
操作按钮:
  - 左侧: "创建短链接" 按钮 (跳转 /links/create)
  - 右侧: 导出 Excel 按钮、刷新按钮
表格列:
  - 短码 (可点击复制，Tooltip 显示完整短链接)
  - 原始URL (超长截断 + Tooltip)
  - 分组名称
  - 今日PV / 累计PV
  - 状态 (Tag: 启用=绿色, 停用=红色)
  - 创建时间
  - 操作列: 编辑 / 停用/启用 / 删除 / 统计
每列支持排序
编辑: 弹出 Modal 表单 (复用创建表单，回填数据)
删除: Popconfirm 二次确认 "确定删除该短链接？"
统计: 跳转 /stats/{id}
分页: 默认每页10条，支持10/20/50切换
```

### 4.5 创建短链接 `/links/create`

```
布局: 居中卡片式表单
表单字段:
  - 原始URL: TextArea, 4行, 必填, placeholder="请输入要缩短的长链接"
  - 分组: Select 下拉, 数据来自 GET /api/group/list
  - 标题: Input, 选填
  - 描述: TextArea, 2行, 选填
  - 重定向类型: Radio.Group: 302临时(默认) / 301永久
  - 有效期: Radio.Group: 永久有效(默认) / 自定义日期
    - 选自定义时展示 DatePicker
提交:
  - "创建" 按钮 (主色调, loading)
  - 成功后 → 展示结果卡片 (短码 + 完整短链接 + 一键复制按钮 + 二维码)
  - "继续创建" 按钮清空表单
  - "返回列表" 按钮跳转 /links
```

### 4.6 分组管理 `/groups`

```
布局: 左侧分组列表 + 右侧分组详情
左侧:
  - 分组列表 (Card.List 或 Menu 风格)
  - 每项显示: 分组名称 + 短链接数量 Badge
  - 底部 "+ 新建分组" 按钮 → 弹出 Modal 表单
  - 右键菜单: 编辑 / 删除
右侧:
  - 选中分组后显示该分组下的短链接小列表
  - 顶部显示分组名称、描述、创建时间
新建/编辑 Modal:
  - 分组名称: Input, 必填, 1-50位
  - 描述: TextArea, 选填, ≤200位
  - 排序: InputNumber
  - 确定按钮
删除:
  - 分组下有短链接时弹窗提示"请先移出或删除 N 条短链接"
```

### 4.7 访问统计 `/stats/:linkId`

```
布局: 顶部链接信息 + 统计卡片 + 图表
链接信息区:
  - 短码 (可复制) + 完整短链接
  - 原始URL
  - 时间范围切换: Tab: 今日 / 近7日 / 近30日
统计卡片(3个):
  - 今日PV + 累计PV
  - 今日UV + 累计UV
  - 今日IP + 累计IP
图表区:
  - 折线图: PV/UV 每日趋势 (双Y轴)
  - 柱状图: 每日IP数
  - 底部: 返回按钮 → /links
数据来源: GET /api/stats/link/{linkId}?range=...
  - range 与 Tab 联动: today / 7d / 30d
```

---

## 五、Axios 封装示例

```typescript
// src/utils/request.ts
import axios from 'axios';
import { message } from 'antd';

const request = axios.create({
  baseURL: 'http://localhost:9000',
  timeout: 10000,
});

// 请求拦截器：自动携带 Token
request.interceptors.request.use((config) => {
  const token = localStorage.getItem('token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// 响应拦截器：统一错误处理
request.interceptors.response.use(
  (response) => {
    const { code, message: msg } = response.data;
    if (code === 401) {
      localStorage.removeItem('token');
      window.location.href = '/login';
      return Promise.reject(new Error(msg));
    }
    if (code !== 200) {
      message.error(msg);
      return Promise.reject(new Error(msg));
    }
    return response.data;
  },
  (error) => {
    message.error('网络异常，请重试');
    return Promise.reject(error);
  }
);

export default request;
```

## 六、Zustand Store 示例

```typescript
// src/store/userStore.ts
import { create } from 'zustand';

interface UserState {
  token: string | null;
  userId: number | null;
  username: string | null;
  realName: string | null;
  setUser: (token: string, userId: number, username: string, realName: string) => void;
  logout: () => void;
}

export const useUserStore = create<UserState>((set) => ({
  token: localStorage.getItem('token'),
  userId: null,
  username: null,
  realName: null,
  setUser: (token, userId, username, realName) => {
    localStorage.setItem('token', token);
    set({ token, userId, username, realName });
  },
  logout: () => {
    localStorage.removeItem('token');
    set({ token: null, userId: null, username: null, realName: null });
  },
}));
```

## 七、生成命令

> 将本文档全文复制，替换下方 `{文档内容}`，发送给 AI：

```
我需要在现有项目中创建一个短链接管理平台的前端。

项目已有一个 React 18 + TypeScript + Ant Design 5 的项目骨架，
路由已配置为 BrowserRouter。

请根据以下 API 文档和页面描述，生成完整的前端代码：

{文档内容}

要求:
1. 生成所有页面的完整代码（不是骨架）
2. 每个页面是独立的 .tsx 文件
3. API 调用封装在 src/api/ 目录下（按模块拆分: auth.ts, link.ts, group.ts, stats.ts）
4. 使用 Zustand 管理用户登录状态
5. 使用 ECharts 绘制统计图表
6. 所有表单有完整的校验规则
7. 样式使用 Ant Design 内置组件，不额外写 CSS
8. 生成项目完整的目录结构
```

---

**文档版本**: v1.0 | **后端端口**: 9000 (Gateway) | **Base URL**: `http://localhost:9000`
