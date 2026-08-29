param([Parameter(Mandatory=$true)][string]$LogPath)
$ErrorActionPreference = 'Stop'
function Assert([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
$records = @(Get-Content -LiteralPath $LogPath -Encoding UTF8 | ForEach-Object { $_ | ConvertFrom-Json })
$app = $records | Where-Object { $_.kind -eq 'argv' -and $_.argv -contains 'app-server' } | Select-Object -First 1
Assert ($null -ne $app) 'app-server command was not launched'
Assert ($app.argv -contains 'web_search=disabled') 'web search was not disabled'
$features = @('apps','auth_elicitation','browser_use','browser_use_external','browser_use_full_cdp_access','code_mode','code_mode_host','code_mode_only','computer_use','goals','guardian_approval','hooks','image_generation','in_app_browser','memories','mentions_v2','multi_agent','multi_agent_v2','personality','plugin_sharing','plugins','remote_compaction_v2','remote_plugin','shell_snapshot','shell_tool','skill_mcp_dependency_install','skill_search','tool_call_mcp_elicitation','tool_suggest','workspace_dependencies')
foreach ($feature in $features) { Assert ($app.argv -contains "features.$feature=false") "feature was not disabled: $feature" }
$requests = @($records | Where-Object kind -eq 'request' | ForEach-Object request)
$threads = @($requests | Where-Object method -eq 'thread/start')
$turns = @($requests | Where-Object method -eq 'turn/start')
$unsubscribes = @($requests | Where-Object method -eq 'thread/unsubscribe')
Assert ($threads.Count -eq 1) "thread/start count was $($threads.Count), expected exactly one"
Assert ($turns.Count -eq 1) "turn/start count was $($turns.Count), expected exactly one"
Assert ($unsubscribes.Count -eq 1) `
    "thread/unsubscribe count was $($unsubscribes.Count), expected exactly one"
$thread = $threads[0]
$turn = $turns[0]
$unsubscribe = $unsubscribes[0]
Assert ($thread.params.model -eq 'gpt-5.6-terra') 'wrong Codex model'
Assert ($thread.params.approvalPolicy -eq 'never') 'approval policy is not never'
Assert ($thread.params.sandbox -eq 'read-only') 'sandbox is not read-only'
Assert ($thread.params.ephemeral -eq $true) 'translation thread is not ephemeral'
Assert ($thread.params.personality -eq 'none') 'personality is not disabled'
Assert ($thread.params.serviceTier -eq 'priority') 'priority service tier was not selected'
Assert ($turn.params.effort -eq 'medium') 'reasoning effort is not medium'
Assert ($turn.params.summary -eq 'none') 'reasoning summary is not disabled'
Assert ($turn.params.serviceTier -eq 'priority') 'turn priority service tier missing'
Assert ($turn.params.input[0].text -eq 'Bloom Boat with Chest') 'source text was modified before Codex'
Assert ($null -ne $turn.params.outputSchema.properties.translation) 'translation output schema missing'
Assert ($null -ne $unsubscribe) 'thread/unsubscribe was not sent'
Write-Output "INLINE_PROTOCOL_OK $LogPath"
