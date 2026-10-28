export function createScrollFollower(threshold = 48) {
  return {
    following: true,
    pause() { this.following = false },
    resume() { this.following = true },
    onScroll(element) {
      if (element) this.following = element.scrollHeight - element.clientHeight - element.scrollTop <= threshold
    },
    onWheel(event) { if (event.deltaY < 0) this.pause() },
    onKey(event) { if (['ArrowUp', 'PageUp', 'Home'].includes(event.key)) this.pause() },
  }
}
