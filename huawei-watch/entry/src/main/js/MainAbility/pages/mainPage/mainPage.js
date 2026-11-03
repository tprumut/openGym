import { Builder, Message, P2pClient } from '../wearenginesdk/wearengine'
import brightness from '@system.brightness'
import { PHONE_APP_FINGERPRINT, PHONE_APP_PACKAGE_NAME } from '../../constants/constants.js'

const WIRE_VERSION = 'opengym-fit-v1'
let p2pClient = new P2pClient()
let messageClient = new Message()
let builderClient = new Builder()

function sendWire(payload, onResult) {
    builderClient.setDescription(JSON.stringify(payload))
    messageClient.builder = builderClient
    p2pClient.send(messageClient, {
        onSuccess: function () { if (onResult) onResult(true) },
        onFailure: function () { if (onResult) onResult(false) },
        onSendResult: function () {},
        onSendProgress: function () {}
    })
}

function parseMessage(data) {
    try {
        const raw = data && typeof data === 'object' && data.data !== undefined ? data.data : data
        const wire = typeof raw === 'string' ? JSON.parse(raw) : raw
        if (!wire || wire.v !== WIRE_VERSION) return null
        return wire
    } catch (e) {
        console.error('openGym message parse failed: ' + e.message)
        return null
    }
}

export default {
    data: {
        workoutName: 'openGym',
        exerciseName: 'Telefon bekleniyor',
        setLabel: 'Saat uygulamasını açın',
        restLabel: '',
        canComplete: false,
        canStart: false,
        status: 'Telefonla bağlantı bekleniyor',
        active: null,
        readySession: null,
        cur: 0,
        setIndex: 0,
        lastEvent: ''
    },
    onInit() {
        this.keepScreenOn()
        p2pClient.setPeerPkgName(PHONE_APP_PACKAGE_NAME)
        p2pClient.setPeerFingerPrint(PHONE_APP_FINGERPRINT)
        this.registerMessage()
    },
    registerMessage() {
        const that = this
        p2pClient.registerReceiver({
            onSuccess: function () { that.status = 'openGym bağlı' },
            onFailure: function () { that.status = 'Bağlantı kurulamadı' },
            onReceiveMessage: function (data) {
                const wire = parseMessage(data)
                if (!wire) return
                if (wire.type === 'snapshot') that.applySnapshot(wire.json)
                if (wire.type === 'open') that.status = 'Antrenman açık'
            }
        })
    },
    applySnapshot(json) {
        try {
            const snapshot = typeof json === 'string' ? JSON.parse(json) : json
            this.active = snapshot && snapshot.active ? snapshot.active : null
            if (!this.active) {
                this.readySession = snapshot && snapshot.ready && snapshot.ready.today ? snapshot.ready.today : null
                this.workoutName = 'openGym'
                this.exerciseName = this.readySession ? this.readySession.name : 'Antrenman yok'
                this.setLabel = this.readySession ? `${this.readySession.entries.length} egzersiz hazır` : 'Telefonda bir antrenman başlatın'
                this.canComplete = false
                this.canStart = !!this.readySession
                this.status = this.readySession ? 'Bilekten başlatılabilir' : 'Hazır'
                return
            }
            this.readySession = null
            this.canStart = false
            this.workoutName = this.active.name || 'Antrenman'
            this.cur = Math.max(0, this.active.cur || 0)
            const entry = this.active.entries && this.active.entries[this.cur]
            this.exerciseName = entry ? entry.name : 'Egzersiz'
            this.setIndex = entry && entry.sets ? entry.sets.findIndex(s => !s.done) : -1
            if (this.setIndex < 0) this.setIndex = entry && entry.sets ? entry.sets.length - 1 : 0
            const set = entry && entry.sets ? entry.sets[this.setIndex] : null
            this.setLabel = set ? `${set.w || 0} ${this.active.unit || 'kg'} × ${set.r || 0}` : 'Set yok'
            this.canComplete = !!set && !set.done
            this.restLabel = this.active.rest && this.active.rest.endsAt ? 'Dinlenme sürüyor' : ''
            this.status = 'Antrenman senkronlandı'
        } catch (e) {
            this.status = 'Snapshot okunamadı'
        }
    },
    startWorkout() {
        if (!this.readySession || !this.readySession.routineIds) return
        const event = {
            id: `fit-start-${Date.now()}`,
            type: 'start',
            wid: `fit-workout-${Date.now()}`,
            routineIds: this.readySession.routineIds,
            at: Date.now()
        }
        const that = this
        sendWire({ v: WIRE_VERSION, event: event }, function (ok) {
            that.lastEvent = ok ? 'Antrenman telefonda başlatıldı' : 'Başlatılamadı; tekrar deneyin'
            if (ok) {
                that.canStart = false
                that.status = 'Antrenman başlatılıyor'
            }
        })
    },
    completeSet() {
        if (!this.active || !this.canComplete) return
        const entry = this.active.entries[this.cur]
        const set = entry && entry.sets ? entry.sets[this.setIndex] : null
        if (!entry || !set) return
        const event = {
            id: `fit-${Date.now()}-${this.cur}-${this.setIndex}`,
            type: 'setDone',
            wid: this.active.id,
            exId: entry.id,
            entryIdx: this.cur,
            setIdx: this.setIndex,
            w: set.w,
            r: set.r,
            sec: set.sec,
            at: Date.now()
        }
        const that = this
        sendWire({ v: WIRE_VERSION, event: event }, function (ok) {
            that.lastEvent = ok ? 'Set telefona gönderildi' : 'Gönderilemedi; tekrar deneyin'
            if (ok) that.canComplete = false
        })
    },
    keepScreenOn() {
        brightness.setKeepScreenOn({ keepScreenOn: true })
    },
    swipeEvent(e) {
        if (e.direction === 'right') {
            p2pClient.unregisterReceiver({ onSuccess: function () {} })
        }
    }
}
