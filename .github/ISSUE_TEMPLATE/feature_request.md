name: '功能建议'

body:
  - type: markdown
    attributes:
      value: |
        ## 提交建议前请注意
        请在提交之前**移除所有隐私和敏感信息**：
        - ~~不要~~ 上传签名文件、密钥库密码或私钥密码
        - ~~不要~~ 上传个人账号密码、手机号、身份证明或真实住址
        - ~~不要~~ 上传包含设备唯一标识的完整日志（可适当脱敏）

  - type: textarea
    id: problem
    attributes:
      label: '相关问题'
      description: '这个功能建议解决了什么问题？'
      placeholder: '例如：希望能在悬浮窗中直接查看当前帧率……'
    validations:
      required: true

  - type: textarea
    id: solution
    attributes:
      label: '期望的方案'
      description: '你希望如何实现这个功能？'
      placeholder: '描述你期望的用户体验或交互方式。'

  - type: textarea
    id: alternatives
    attributes:
      label: '替代方案（可选）'
      description: '有没有考虑过其他可行的替代方案？'
      placeholder: '如果没有，可以留空。'

  - type: textarea
    id: additional
    attributes:
      label: '补充信息（可选）'
      description: '还有其他想补充的信息吗？'
