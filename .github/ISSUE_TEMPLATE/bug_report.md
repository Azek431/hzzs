name: 'Bug 报告'

body:
  - type: markdown
    attributes:
      value: |
        ## 提交 Bug 前请注意
        请在提交之前**移除所有隐私和敏感信息**：
        - ~~不要~~ 上传签名文件、密钥库密码或私钥密码
        - ~~不要~~ 上传个人账号密码、手机号、身份证明或真实住址
        - ~~不要~~ 上传包含设备唯一标识的完整日志（可适当脱敏）

  - type: textarea
    id: description
    attributes:
      label: '问题描述'
      description: '清晰简洁地描述这个 Bug 是什么。'
      placeholder: '例如：点击 XX 按钮后应用崩溃……'
    validations:
      required: true

  - type: textarea
    id: reproduce
    attributes:
      label: '复现步骤'
      description: '描述复现该问题的步骤。'
      placeholder: |
        1. 打开应用
        2. 点击 XX
        3. 观察到 YY
    validations:
      required: true

  - type: dropdown
    id: environment
    attributes:
      label: '运行环境'
      description: '这个问题出现在什么环境下？'
      options:
        - Debug 构建（开发版）
        - Release 构建（正式版）
        - 两者都有
    validations:
      required: true

  - type: input
    id: device
    attributes:
      label: '设备型号'
      placeholder: '例如：小米 13、Samsung Galaxy S22'

  - type: input
    id: android_version
    attributes:
      label: 'Android 版本'
      placeholder: '例如：Android 13 (API 33)'

  - type: input
    id: app_version
    attributes:
      label: '应用版本'
      placeholder: '例如：0.1.0'

  - type: textarea
    id: logs
    attributes:
      label: '相关日志（可选）'
      description: '如有必要，可粘贴关键日志片段。请确保已移除设备序列号、用户名等隐私信息。'
      placeholder: '请粘贴日志，但不要包含签名文件、密钥或个人隐私。'
