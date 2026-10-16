package one.meshcore.shared.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector
import one.meshcore.shared.navigation.AppTab
import one.meshcore.shared.ui.icons.symbols.MaterialSymbols

/**
 * Single source of truth for Material Symbols used by shared Compose chrome.
 *
 * Policy: selected / active nav uses **filled** (FILL=1); unselected uses **outlined** (FILL=0,
 * or the auto-mirrored filled/outlined pair). Optical size is the Material default 24.dp unless a
 * dense chip needs 20.dp.
 */
object AppIcons {
    // Primary navigation (selected = filled, unselected = outlined).
    val Chats: ImageVector get() = MaterialSymbols.AutoMirrored.Outlined.Chat
    val ChatsSelected: ImageVector get() = MaterialSymbols.AutoMirrored.Filled.Chat
    val Nodes: ImageVector get() = MaterialSymbols.Outlined.CellTower
    val NodesSelected: ImageVector get() = MaterialSymbols.Filled.CellTower
    val Map: ImageVector get() = MaterialSymbols.Outlined.Map
    val MapSelected: ImageVector get() = MaterialSymbols.Filled.Map
    val Tools: ImageVector get() = MaterialSymbols.Outlined.Build
    val ToolsSelected: ImageVector get() = MaterialSymbols.Filled.Build
    val Settings: ImageVector get() = MaterialSymbols.Outlined.Settings
    val SettingsSelected: ImageVector get() = MaterialSymbols.Filled.Settings

    // Toolbar / sheet chrome
    val Back: ImageVector get() = MaterialSymbols.AutoMirrored.Filled.ArrowBack
    val Close: ImageVector get() = MaterialSymbols.Filled.Close
    val Clear: ImageVector get() = MaterialSymbols.Filled.Clear
    val Add: ImageVector get() = MaterialSymbols.Filled.Add
    val AddCircle: ImageVector get() = MaterialSymbols.Outlined.AddCircle
    val AddCircleFill: ImageVector get() = MaterialSymbols.Filled.AddCircle
    val Remove: ImageVector get() = MaterialSymbols.Filled.Remove
    val Search: ImageVector get() = MaterialSymbols.Filled.Search
    val MoreVert: ImageVector get() = MaterialSymbols.Filled.MoreVert
    val Edit: ImageVector get() = MaterialSymbols.Filled.Edit
    val Info: ImageVector get() = MaterialSymbols.Filled.Info
    val Delete: ImageVector get() = MaterialSymbols.Filled.Delete
    val Check: ImageVector get() = MaterialSymbols.Filled.Check
    val CheckCircle: ImageVector get() = MaterialSymbols.Filled.CheckCircle
    val Circle: ImageVector get() = MaterialSymbols.Filled.Circle
    val Code: ImageVector get() = MaterialSymbols.Filled.Code
    val Warning: ImageVector get() = MaterialSymbols.Filled.Warning
    val Error: ImageVector get() = MaterialSymbols.Filled.Error
    val Sync: ImageVector get() = MaterialSymbols.Filled.Sync
    val Wifi: ImageVector get() = MaterialSymbols.Filled.Wifi
    val WifiTethering: ImageVector get() = MaterialSymbols.Filled.WifiTethering
    val Bluetooth: ImageVector get() = MaterialSymbols.Filled.Bluetooth
    val Bolt: ImageVector get() = MaterialSymbols.Filled.Bolt
    val Refresh: ImageVector get() = MaterialSymbols.Filled.Refresh
    val Repeat: ImageVector get() = MaterialSymbols.Filled.Repeat
    val RecordVoiceOver: ImageVector get() = MaterialSymbols.Filled.RecordVoiceOver
    val Sort: ImageVector get() = MaterialSymbols.AutoMirrored.Filled.Sort
    val Send: ImageVector get() = MaterialSymbols.AutoMirrored.Filled.Send
    val ExpandMore: ImageVector get() = MaterialSymbols.Filled.ExpandMore
    val KeyboardArrowUp: ImageVector get() = MaterialSymbols.Filled.KeyboardArrowUp
    val KeyboardArrowDown: ImageVector get() = MaterialSymbols.Filled.KeyboardArrowDown
    val KeyboardArrowLeft: ImageVector get() = MaterialSymbols.AutoMirrored.Filled.KeyboardArrowLeft
    val KeyboardArrowRight: ImageVector get() = MaterialSymbols.AutoMirrored.Filled.KeyboardArrowRight
    val KeyboardTab: ImageVector get() = MaterialSymbols.AutoMirrored.Filled.KeyboardTab
    val KeyboardHide: ImageVector get() = MaterialSymbols.Filled.KeyboardHide
    val ContentPaste: ImageVector get() = MaterialSymbols.Filled.ContentPaste
    val StopCircle: ImageVector get() = MaterialSymbols.Filled.StopCircle

    // Domain chrome
    val Lock: ImageVector get() = MaterialSymbols.Filled.Lock
    val LockOpen: ImageVector get() = MaterialSymbols.Filled.LockOpen
    val Key: ImageVector get() = MaterialSymbols.Filled.Key
    val QrCodeScanner: ImageVector get() = MaterialSymbols.Filled.QrCodeScanner
    val Star: ImageVector get() = MaterialSymbols.Filled.Star
    val StarSlash: ImageVector get() = StarSlashGlyph
    val PushPin: ImageVector get() = PushPinGlyph
    val Favorite: ImageVector get() = MaterialSymbols.Filled.Favorite
    val Feedback: ImageVector get() = MaterialSymbols.Filled.Feedback
    val Block: ImageVector get() = MaterialSymbols.Filled.Block
    val DoNotTouch: ImageVector get() = MaterialSymbols.Filled.DoNotTouch
    val NoAccounts: ImageVector get() = MaterialSymbols.Filled.NoAccounts
    val Bookmark: ImageVector get() = MaterialSymbols.Filled.Bookmark
    val Business: ImageVector get() = MaterialSymbols.Filled.Business
    val CalendarMonth: ImageVector get() = MaterialSymbols.Filled.CalendarMonth
    val LocationOn: ImageVector get() = MaterialSymbols.Filled.LocationOn
    val LooksOne: ImageVector get() = MaterialSymbols.Filled.LooksOne
    val MeetingRoom: ImageVector get() = MaterialSymbols.Filled.MeetingRoom
    val Memory: ImageVector get() = MaterialSymbols.Filled.Memory
    val Straighten: ImageVector get() = MaterialSymbols.Filled.Straighten
    val MyLocation: ImageVector get() = MaterialSymbols.Filled.MyLocation
    val Layers: ImageVector get() = MaterialSymbols.Filled.Layers
    val Person: ImageVector get() = MaterialSymbols.Filled.Person
    val PersonAdd: ImageVector get() = MaterialSymbols.Filled.PersonAdd
    val PersonOff: ImageVector get() = MaterialSymbols.Filled.PersonOff
    val Place: ImageVector get() = MaterialSymbols.Filled.Place
    val AccessTime: ImageVector get() = MaterialSymbols.Filled.AccessTime
    val AccountCircle: ImageVector get() = MaterialSymbols.Filled.AccountCircle
    val Group: ImageVector get() = MaterialSymbols.Filled.Group
    val GroupOff: ImageVector get() = MaterialSymbols.Filled.GroupOff
    val Groups: ImageVector get() = MaterialSymbols.Filled.Groups
    val HighlightOff: ImageVector get() = MaterialSymbols.Filled.HighlightOff
    val History: ImageVector get() = MaterialSymbols.Filled.History
    val Home: ImageVector get() = MaterialSymbols.Filled.Home
    val Forum: ImageVector get() = MaterialSymbols.Filled.Forum
    val FormatQuote: ImageVector get() = MaterialSymbols.Filled.FormatQuote
    val FrontHand: ImageVector get() = MaterialSymbols.Filled.FrontHand
    val GraphicEq: ImageVector get() = MaterialSymbols.Filled.GraphicEq
    val Tag: ImageVector get() = MaterialSymbols.Filled.Tag
    val AlternateEmail: ImageVector get() = MaterialSymbols.Filled.AlternateEmail
    val CellTower: ImageVector get() = MaterialSymbols.Filled.CellTower
    val CellTowerOff: ImageVector get() = MaterialSymbols.Outlined.CellTower
    val BatteryFull: ImageVector get() = MaterialSymbols.Filled.BatteryFull
    val BatteryStd: ImageVector get() = MaterialSymbols.Filled.BatteryStd
    val Sensors: ImageVector get() = MaterialSymbols.Filled.Sensors
    val SentimentSatisfied: ImageVector get() = MaterialSymbols.Filled.SentimentSatisfied
    val Notifications: ImageVector get() = MaterialSymbols.Filled.Notifications
    val Palette: ImageVector get() = MaterialSymbols.Filled.Palette
    val Brightness: ImageVector get() = MaterialSymbols.Filled.Brightness6
    val CloudDownload: ImageVector get() = MaterialSymbols.Filled.CloudDownload
    val Archive: ImageVector get() = MaterialSymbols.Filled.Archive
    val Save: ImageVector get() = MaterialSymbols.Filled.Save
    val BugReport: ImageVector get() = MaterialSymbols.Filled.BugReport
    val ChevronRight: ImageVector get() = MaterialSymbols.Filled.ChevronRight
    val Timeline: ImageVector get() = MaterialSymbols.Filled.Timeline
    val Visibility: ImageVector get() = MaterialSymbols.Filled.Visibility
    val VisibilityOff: ImageVector get() = MaterialSymbols.Filled.VisibilityOff
    val ListAlt: ImageVector get() = MaterialSymbols.AutoMirrored.Filled.ListAlt
    val Terminal: ImageVector get() = MaterialSymbols.Filled.Terminal
    val Link: ImageVector get() = MaterialSymbols.Filled.Link
    val LinkOff: ImageVector get() = MaterialSymbols.Filled.LinkOff
    val Share: ImageVector get() = MaterialSymbols.Filled.Share
    val Shield: ImageVector get() = MaterialSymbols.Filled.Shield
    val Smartphone: ImageVector get() = MaterialSymbols.Filled.Smartphone
    val ShowChart: ImageVector get() = MaterialSymbols.AutoMirrored.Filled.ShowChart
    val Pause: ImageVector get() = MaterialSymbols.Filled.Pause
    val PlayArrow: ImageVector get() = MaterialSymbols.Filled.PlayArrow

    // Menu / action-sheet glyphs (24.dp Material defaults).
    val FilterList: ImageVector get() = MaterialSymbols.Filled.FilterList
    val NotificationsOff: ImageVector get() = MaterialSymbols.Filled.NotificationsOff
    val StarBorder: ImageVector get() = MaterialSymbols.Outlined.Star
    val Storage: ImageVector get() = MaterialSymbols.Filled.Storage
    val Route: ImageVector get() = MaterialSymbols.Filled.Route
    val Public: ImageVector get() = MaterialSymbols.Filled.Public
    val Reply: ImageVector get() = MaterialSymbols.AutoMirrored.Filled.Reply
    val Redo: ImageVector get() = MaterialSymbols.AutoMirrored.Filled.Redo
    val ContentCopy: ImageVector get() = MaterialSymbols.Filled.ContentCopy
    val Translate: ImageVector get() = MaterialSymbols.Filled.Translate
    val Chat: ImageVector get() = MaterialSymbols.AutoMirrored.Filled.Chat

    /** Tab icon for [tab]; [selected] switches filled vs outlined. */
    fun tab(tab: AppTab, selected: Boolean): ImageVector = when (tab) {
        AppTab.CHATS -> if (selected) ChatsSelected else Chats
        AppTab.NODES -> if (selected) NodesSelected else Nodes
        AppTab.MAP -> if (selected) MapSelected else Map
        AppTab.TOOLS -> if (selected) ToolsSelected else Tools
        AppTab.SETTINGS -> if (selected) SettingsSelected else Settings
    }
}
